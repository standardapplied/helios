/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.process;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.core.test.Await;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class BoundedProcessTest {

  private static final String BASH = "/bin/bash";
  private static final Duration NEVER_REACHED = Duration.ofMinutes(10);
  private static final Duration SHORT = Duration.ofMillis(300);

  @BeforeAll
  static void requireBash() {
    assumeTrue(Files.isExecutable(Path.of(BASH)), "/bin/bash is required");
  }

  @AfterEach
  void reapStragglers() {
    ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
  }

  private static BoundedProcess.Builder bash(String script) {
    return BoundedProcess.newBuilder(List.of(BASH, "-c", script)).withTimeout(NEVER_REACHED);
  }

  private static String text(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static ProcessOutcome run(BoundedProcess.Builder builder) throws Exception {
    try (var process = builder.start()) {
      process.stdin().close();
      return process.await();
    }
  }

  @Test
  void capturesExitCodeAndBothStreams() throws Exception {
    var outcome = run(bash("echo out; echo err >&2; exit 3"));

    assertEquals(3, outcome.exitCode());
    assertEquals("out\n", text(outcome.stdout()));
    assertEquals("err\n", text(outcome.stderr()));
    assertFalse(outcome.timedOut());
    assertFalse(outcome.truncated());
  }

  @Test
  void childSeesExactlyTheGivenEnvironment() throws Exception {
    var builder =
        BoundedProcess.newBuilder(List.of("/usr/bin/env"))
            .withEnvironment(Map.of("ONLY", "this"))
            .withTimeout(NEVER_REACHED);

    assertEquals("ONLY=this\n", text(run(builder).stdout()));
  }

  @Test
  void childFeedsOnWhatIsWrittenToStdin() throws Exception {
    try (var process = bash("cat").start()) {
      try (var stdin = process.stdin()) {
        stdin.write("fed".getBytes(StandardCharsets.UTF_8));
      }
      assertEquals("fed", text(process.await().stdout()));
    }
  }

  @Test
  void perCallDirectoryIsCreatedWithThePrefixAndRemovedOnClose() throws Exception {
    Path directory;
    try (var process = bash("pwd").withTempDirectoryPrefix("helios-bp-test-").start()) {
      process.stdin().close();
      directory = Path.of(text(process.await().stdout()).strip());
      assertTrue(Files.isDirectory(directory));
      assertTrue(directory.getFileName().toString().startsWith("helios-bp-test-"));
    }
    assertFalse(Files.exists(directory));
  }

  @Test
  void perCallDirectoryIsRemovedWithWhatTheChildWroteInIt() throws Exception {
    Path directory;
    try (var process = bash("mkdir -p a/b && echo x > a/b/f && pwd").start()) {
      process.stdin().close();
      directory = Path.of(text(process.await().stdout()).strip());
    }
    assertFalse(Files.exists(directory));
  }

  @Test
  void givenWorkingDirectoryIsUsedAndKept(@TempDir Path tmp) throws Exception {
    var outcome = run(bash("pwd").withWorkingDirectory(tmp));

    assertEquals(tmp.toRealPath().toString(), text(outcome.stdout()).strip());
    assertTrue(Files.isDirectory(tmp));
  }

  @Test
  void outputPastTheCapIsDroppedAndMarked() throws Exception {
    var outcome = run(bash("printf 'a%.0s' $(seq 1 500)").withMaxOutputBytes(100));

    assertTrue(outcome.truncated());
    assertEquals("a".repeat(100) + "\n[truncated: output exceeded cap]", text(outcome.stdout()));
    assertArrayEquals(new byte[0], outcome.stderr());
  }

  @Test
  void timeoutKillsTheProcess() throws Exception {
    var outcome = run(bash("exec sleep 600").withTimeout(SHORT));

    assertTrue(outcome.timedOut());
    assertEquals(-1, outcome.exitCode());
  }

  @Test
  void timeoutForciblyKillsAProcessIgnoringSigterm() throws Exception {
    var outcome = run(bash("trap '' TERM; exec sleep 600").withTimeout(SHORT));

    assertTrue(outcome.timedOut());
    assertEquals(-1, outcome.exitCode());
  }

  @Test
  void killEndsTheProcessAndItsDescendants(@TempDir Path tmp) throws Exception {
    try (var process =
        bash("sleep 600 & echo $! > child; wait").withWorkingDirectory(tmp).start()) {
      process.stdin().close();
      Await.until("the descendant to start", () -> Files.exists(tmp.resolve("child")));
      Await.until("the pid to be written", () -> !readPid(tmp).isEmpty());
      var child = ProcessHandle.of(Long.parseLong(readPid(tmp))).orElseThrow();

      process.kill();
      var outcome = process.await();

      assertFalse(outcome.timedOut());
      Await.until("the descendant to die", () -> !child.isAlive());
    }
  }

  private static String readPid(Path dir) {
    try {
      return Files.readString(dir.resolve("child")).strip();
    } catch (IOException e) {
      return "";
    }
  }

  @Test
  void interruptWhileAwaitingPropagatesAndLeavesTheProcessToTheCaller() throws Exception {
    try (var process = bash("exec sleep 600").start()) {
      process.stdin().close();
      var thrown = new CompletableFuture<Throwable>();
      var waiter =
          Thread.startVirtualThread(
              () -> {
                try {
                  process.await();
                  thrown.complete(null);
                } catch (InterruptedException e) {
                  thrown.complete(e);
                }
              });

      waiter.interrupt();

      assertInstanceOf(InterruptedException.class, Await.value("the interrupted await", thrown));
      assertTrue(process.process().isAlive());
      process.kill();
      Await.termination("the killed process", process.process());
    }
  }

  @Test
  void failedStartRemovesThePerCallDirectory() {
    var prefix = "helios-bp-fail-" + UUID.randomUUID() + "-";
    var builder =
        BoundedProcess.newBuilder(List.of("/nonexistent/zzzzzz")).withTempDirectoryPrefix(prefix);

    assertThrows(IOException.class, builder::start);

    var tmpdir = Path.of(System.getProperty("java.io.tmpdir"));
    try (var entries = Files.list(tmpdir)) {
      assertTrue(entries.noneMatch(p -> p.getFileName().toString().startsWith(prefix)));
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void builderRejectsInvalidSettings() {
    assertThrows(IllegalArgumentException.class, () -> BoundedProcess.newBuilder(List.of()));
    var builder = BoundedProcess.newBuilder(List.of(BASH));
    assertThrows(IllegalArgumentException.class, () -> builder.withTimeout(null));
    assertThrows(IllegalArgumentException.class, () -> builder.withTimeout(Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> builder.withTimeout(Duration.ofSeconds(-1)));
    assertThrows(IllegalArgumentException.class, () -> builder.withMaxOutputBytes(0));
    assertThrows(NullPointerException.class, () -> builder.withTempDirectoryPrefix(null));
  }
}
