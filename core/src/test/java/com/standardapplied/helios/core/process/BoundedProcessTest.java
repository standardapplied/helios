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
import java.util.Comparator;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

  /**
   * The descendant inherits the child's stdout, so if it outlived the timeout the capture would
   * wait for it: {@link BoundedProcess#await()} returning is the proof that the timeout ended it
   * too. The timeout starts in {@code await()}, after the descendant is known to run.
   */
  @Test
  void timeoutEndsTheDescendantsHoldingTheOutput(@TempDir Path tmp) throws Exception {
    try (var process =
        bash("sleep 600 & echo $! > child; wait")
            .withWorkingDirectory(tmp)
            .withTimeout(SHORT)
            .start()) {
      process.stdin().close();
      Await.until("the pid to be written", () -> !readPid(tmp).isEmpty());
      var child = ProcessHandle.of(Long.parseLong(readPid(tmp))).orElseThrow();

      var outcome = process.await();

      assertTrue(outcome.timedOut());
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

  /**
   * Whatever the child leaves in its working directory, closing the process does not throw: the
   * cleanup is best-effort, and a tool call's result must not be lost to it. Each script prints the
   * directory so the test can restore and remove what the cleanup could not.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "pwd; rm -rf \"$PWD\"",
        "pwd; mkdir sealed && touch sealed/f && chmod 555 sealed",
        "pwd; mkdir locked && touch locked/f && chmod 000 locked"
      })
  void closeSurvivesAWorkingDirectoryItCannotFullyRemove(String script) throws Exception {
    Path directory;
    try (var process = bash(script).start()) {
      process.stdin().close();
      directory = Path.of(text(process.await().stdout()).strip());
    }
    restoreAndRemove(directory);
  }

  private static void restoreAndRemove(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    try (var entries = Files.walk(directory, 1)) {
      for (var entry : entries.toList()) {
        entry.toFile().setReadable(true);
        entry.toFile().setWritable(true);
        entry.toFile().setExecutable(true);
      }
    }
    try (var entries = Files.walk(directory)) {
      for (var entry : entries.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(entry);
      }
    }
  }

  @Test
  void failedStartLeavesACallerSuppliedDirectory(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("kept"), "caller's file");
    var builder =
        BoundedProcess.newBuilder(List.of("/nonexistent/zzzzzz")).withWorkingDirectory(tmp);

    assertThrows(IOException.class, builder::start);

    assertEquals("caller's file", Files.readString(tmp.resolve("kept")));
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
