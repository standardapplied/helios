/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.process;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.runtime.CancellationToken;
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
  private static final String TRUNCATION_MARKER = "\n[truncated: output exceeded cap]";

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
      return process.await(new CancellationToken());
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
      assertEquals("fed", text(process.await(new CancellationToken()).stdout()));
    }
  }

  @Test
  void perCallDirectoryIsCreatedWithThePrefixAndRemovedOnClose() throws Exception {
    Path directory;
    try (var process = bash("pwd").withTempDirectoryPrefix("helios-bp-test-").start()) {
      process.stdin().close();
      directory = Path.of(text(process.await(new CancellationToken()).stdout()).strip());
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
      directory = Path.of(text(process.await(new CancellationToken()).stdout()).strip());
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
  void eachStreamReportsItsOwnTruncation() throws Exception {
    var outcome = run(bash("printf 'a%.0s' $(seq 1 500) >&2; printf out").withMaxOutputBytes(100));

    assertFalse(outcome.stdoutTruncated());
    assertTrue(outcome.stderrTruncated());
    assertTrue(outcome.truncated());
  }

  @Test
  void aCutStreamDropsThePartOfASecretItEndsIn() throws Exception {
    var secrets = new SecretRegistry();
    secrets.register("API_KEY", "sk-test-0123456789abcdef");
    var outcome =
        run(
            bash("printf 'a%.0s' $(seq 1 90); printf sk-test-0123456789abcdef; printf sk-tes >&2")
                .withMaxOutputBytes(100)
                .withSecretRegistry(secrets));

    assertEquals("a".repeat(90) + "\n[truncated: output exceeded cap]", text(outcome.stdout()));
    assertEquals("sk-tes", text(outcome.stderr()));
  }

  @Test
  void aCutStreamKeepsACompleteSecretEndingInItsOwnPrefixForRedaction() throws Exception {
    var secrets = new SecretRegistry();
    secrets.register("TOKEN", "abcd1234a");
    var outcome =
        run(bash("printf 'value=abcd1234a!'").withMaxOutputBytes(15).withSecretRegistry(secrets));

    var redacted = secrets.redactor().redact(text(outcome.stdout()));
    assertEquals("value=<redacted:TOKEN>\n[truncated: output exceeded cap]", redacted.text());
    assertEquals(Map.of("TOKEN", 1), redacted.counts());
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
      var outcome = process.await(new CancellationToken());

      assertFalse(outcome.timedOut());
      Await.until("the descendant to die", () -> !child.isAlive());
    }
  }

  /**
   * The descendant inherits the child's stdout and would hold the capture open if it outlived the
   * timeout. The timeout starts in {@link BoundedProcess#await(CancellationToken)}, after the
   * descendant is known to run.
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

      var outcome = process.await(new CancellationToken());

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
  void aBackgroundedDescendantHoldingTheOutputDoesNotHoldUpTheCall(@TempDir Path tmp)
      throws Exception {
    try (var process =
        BoundedProcess.newBuilder(
                List.of("/bin/sh", "-c", "sleep 600 & echo $! > child; echo started"))
            .withWorkingDirectory(tmp)
            .withTimeout(NEVER_REACHED)
            .start()) {
      process.stdin().close();

      var outcome = process.await(new CancellationToken());

      assertEquals(0, outcome.exitCode());
      assertEquals("started\n" + TRUNCATION_MARKER, text(outcome.stdout()));
      assertTrue(outcome.stdoutTruncated());
      assertFalse(outcome.timedOut());
    } finally {
      destroyRecorded(tmp);
    }
  }

  @Test
  void anInterruptedAwaitKillsTheChildAndKeepsTheInterruptStatus() throws Exception {
    try (var process = bash("exec sleep 600").start()) {
      var child = process.process().toHandle();
      try {
        process.stdin().close();

        var interrupted = interruptAwaiting(process);

        assertTrue(interrupted, "the interrupt status is set again");
        Await.value("the killed child to end", child.onExit());
      } finally {
        child.destroyForcibly();
      }
    }
  }

  /**
   * The interrupt status is already set when the reaping starts, so a reaping that an interrupt
   * cuts short would never reach the forcible kill this child needs.
   */
  @Test
  void anInterruptedAwaitForciblyKillsAChildIgnoringSigterm(@TempDir Path tmp) throws Exception {
    try (var process =
        bash("trap '' TERM; echo $$ > child; exec sleep 600").withWorkingDirectory(tmp).start()) {
      var child = process.process().toHandle();
      try {
        process.stdin().close();
        Await.until("SIGTERM to be ignored", () -> !readPid(tmp).isEmpty());

        assertTrue(interruptAwaiting(process), "the interrupt status is set again");
        Await.value("the forcibly killed child to end", child.onExit());
      } finally {
        child.destroyForcibly();
      }
    }
  }

  /**
   * Interrupt a thread blocked in {@code await}; return whether its interrupt status was set when
   * {@code await} threw.
   */
  private static boolean interruptAwaiting(BoundedProcess process) {
    var interrupted = new CompletableFuture<Boolean>();
    var waiter =
        Thread.startVirtualThread(
            () -> {
              try {
                process.await(new CancellationToken());
                interrupted.completeExceptionally(new AssertionError("await returned"));
              } catch (InterruptedException e) {
                interrupted.complete(Thread.currentThread().isInterrupted());
              }
            });
    waiter.interrupt();
    return Await.value("the interrupted await", interrupted);
  }

  @Test
  void cancellingTheTokenKillsTheChild() throws Exception {
    try (var process = bash("exec sleep 600").start()) {
      var child = process.process().toHandle();
      try {
        process.stdin().close();
        var token = new CancellationToken();
        var outcome = CompletableFuture.supplyAsync(() -> awaitUnchecked(process, token));

        token.cancel("test");

        var cancelled = Await.value("the cancelled await", outcome);
        assertEquals(-1, cancelled.exitCode());
        assertFalse(cancelled.timedOut());
        Await.value("the killed child to end", child.onExit());
        assertEquals(0, token.activeCallbackCountForTests());
      } finally {
        child.destroyForcibly();
      }
    }
  }

  @Test
  void cancellingTheTokenForciblyKillsAChildIgnoringSigterm(@TempDir Path tmp) throws Exception {
    try (var process =
        bash("trap '' TERM; echo $$ > child; exec sleep 600").withWorkingDirectory(tmp).start()) {
      var child = process.process().toHandle();
      try {
        process.stdin().close();
        Await.until("SIGTERM to be ignored", () -> !readPid(tmp).isEmpty());
        var token = new CancellationToken();
        token.cancel("before the wait");

        var outcome = process.await(token);

        assertEquals(-1, outcome.exitCode());
        assertFalse(outcome.timedOut());
        Await.value("the forcibly killed child to end", child.onExit());
      } finally {
        child.destroyForcibly();
      }
    }
  }

  @Test
  void aCompletedAwaitLeavesNoCallbackOnTheToken() throws Exception {
    var token = new CancellationToken();
    try (var process = bash("exit 0").start()) {
      process.stdin().close();

      assertEquals(0, process.await(token).exitCode());
    }
    assertEquals(0, token.activeCallbackCountForTests());
  }

  /**
   * The grandchild ignores SIGTERM and the child dies of it, so the child's exit says nothing about
   * the grandchild: only the forcible kill of the descendants captured before the first signal ends
   * it.
   */
  @Test
  void timeoutForciblyKillsADescendantIgnoringSigtermWhoseParentDied(@TempDir Path tmp)
      throws Exception {
    try (var process =
        bash("(trap '' TERM; echo $BASHPID > child; exec sleep 600) & exec sleep 600")
            .withWorkingDirectory(tmp)
            .withTimeout(SHORT)
            .start()) {
      process.stdin().close();
      Await.until("the grandchild to ignore SIGTERM", () -> !readPid(tmp).isEmpty());
      var grandchild = ProcessHandle.of(Long.parseLong(readPid(tmp))).orElseThrow();

      var outcome = process.await(new CancellationToken());

      assertTrue(outcome.timedOut());
      Await.value("the grandchild to end", grandchild.onExit());
    } finally {
      destroyRecorded(tmp);
    }
  }

  private static ProcessOutcome awaitUnchecked(BoundedProcess process, CancellationToken token) {
    try {
      return process.await(token);
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  /** Forcibly end the process whose pid the test's fixture wrote, and wait for it to end. */
  private static void destroyRecorded(Path dir) {
    var pid = readPid(dir);
    if (!pid.isEmpty()) {
      ProcessHandle.of(Long.parseLong(pid))
          .ifPresent(
              handle -> {
                handle.destroyForcibly();
                Await.value("the recorded process to end", handle.onExit());
              });
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
      directory = Path.of(text(process.await(new CancellationToken()).stdout()).strip());
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
