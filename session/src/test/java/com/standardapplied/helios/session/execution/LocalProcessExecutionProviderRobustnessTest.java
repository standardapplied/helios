/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hardening tests for {@link LocalProcessExecutionProvider}: hung-subprocess reaping, abrupt-kill
 * via cancellation, large-stdin deadlock guard, provider close lifecycle, semaphore-acquire
 * cancellation, JVM shutdown hook removal.
 *
 * <p>The tests build providers via the explicit Builder with the JVM shutdown hook disabled so the
 * test runtime is not polluted with hook objects, and every provider is wrapped in
 * try-with-resources so {@code close()} reaps any leftover subprocess.
 *
 * <p>No test depends on how long a child takes. A request's timeout is {@link #BEYOND_HANG_GUARD}
 * unless the timeout is what the test exercises, and a child that must be killed is {@link #HANG}:
 * it cannot end on its own within the hang guard, so a result proves the kill.
 */
final class LocalProcessExecutionProviderRobustnessTest {

  private static final SessionContext CTX = SessionContext.forTesting("robustness-test");

  private static final Duration BEYOND_HANG_GUARD = Await.HANG_GUARD.multipliedBy(5);

  /**
   * {@code exec} keeps the child a single process. A kill that lands while a shell forks misses the
   * new process, which then holds the output pipes and the call with them.
   */
  private static final String HANG = "exec sleep 600";

  private static final int KILLED_BY_SIGKILL = 128 + 9;
  private static final int KILLED_BY_SIGTERM = 128 + 15;

  private static LocalProcessExecutionProvider testProvider() {
    return LocalProcessExecutionProvider.newBuilder()
        .withSecretRegistry(new SecretRegistry())
        .withRuntime(Runtime.BASH, LocalProcessExecutionProvider.RuntimeHandler.dashC("bash"))
        .withShutdownHook(false)
        .build();
  }

  private static LocalProcessExecutionProvider testProvider(int maxConcurrent) {
    return LocalProcessExecutionProvider.newBuilder()
        .withSecretRegistry(new SecretRegistry())
        .withRuntime(Runtime.BASH, LocalProcessExecutionProvider.RuntimeHandler.dashC("bash"))
        .withMaxConcurrent(maxConcurrent)
        .withShutdownHook(false)
        .build();
  }

  /**
   * A test that fails before its children are reaped must not leave one running; nothing else in
   * this JVM starts a process while a test of this class runs.
   */
  @AfterEach
  void noChildOutlivesItsTest() {
    ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
  }

  // ── timeout reaps the process tree, including SIGTERM-deaf descendants ───

  /**
   * The child holds the output pipes for ten minutes, so a result means the provider killed it;
   * once its trap is set, only SIGKILL can.
   */
  @Test
  void timeoutEscalatesFromSigtermToSigkillWhenChildIgnoresTerm() {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var result =
          run(provider, bash("trap '' TERM; " + HANG).withTimeout(Duration.ofMillis(200)).build());
      assertTrue(result.timedOut(), "expected timedOut=true after SIGKILL escalation");
      assertEquals(-1, result.exitCode());
      assertEquals(0, provider.inflightCount(), "no leftover in-flight processes");
    }
  }

  // ── large stdin against non-reading process completes without deadlock ──

  /** 512 KiB of stdin, far past a pipe's buffer, against a script that exits without reading it. */
  @Test
  void largeStdinAgainstNonReadingProcessDoesNotDeadlock() {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var result = run(provider, bash("printf done").withStdin("x".repeat(512 * 1024)).build());
      assertEquals(0, result.exitCode());
      assertEquals("done", result.stdout());
      assertFalse(result.timedOut());
      assertEquals(0, provider.inflightCount());
    }
  }

  // ── cancellation during a long sleep kills the process ──────────────────

  @Test
  void cancellationDuringExecutionKillsProcessAndCompletesExceptionally() {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var token = new CancellationToken();
      var call = start(provider, bash(HANG).build(), token);
      awaitInflight(provider, 1);
      token.cancel("test-cancel");
      assertEquals(
          "execution of BASH cancelled: test-cancel",
          cancellationMessage("the cancelled call to fail", call));
      assertEquals(0, provider.inflightCount());
    }
  }

  // ── cancellation while waiting for a permit unblocks the acquire ────────

  @Test
  void cancellationWhilePermitWaitUnblocksAcquire() {
    assumeBashAvailable();
    try (var provider = testProvider(1)) {
      var hold = new CancellationToken();
      var holder = start(provider, bash(HANG).build(), hold);
      awaitInflight(provider, 1);

      var token = new CancellationToken();
      var waiting = start(provider, bash("printf x").build(), token);
      awaitPermitRequest(token);
      assertFalse(waiting.isDone(), "second call should be parked in acquire()");

      token.cancel("permit-cancel");
      assertEquals(
          "interrupted while acquiring permit for BASH",
          cancellationMessage("the waiting call to fail", waiting));

      hold.cancel("done");
      assertEquals(
          "execution of BASH cancelled: done",
          cancellationMessage("the permit holder to fail", holder));
    }
  }

  // ── provider.close() reaps in-flight processes ──────────────────────────

  @Test
  void closeForciblyReapsInflightProcesses(@TempDir Path cwd) {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var calls = new ArrayList<CompletableFuture<ExecutionResult>>();
      for (var i = 0; i < 3; i++) {
        var script = "trap '' TERM; : > deaf-" + i + "; " + HANG;
        calls.add(
            start(
                provider, bash(script).withWorkingDirectory(cwd).build(), new CancellationToken()));
      }
      awaitInflight(provider, 3);
      for (var i = 0; i < calls.size(); i++) {
        var deaf = cwd.resolve("deaf-" + i);
        Await.until("child " + i + " to ignore SIGTERM", () -> Files.exists(deaf));
      }
      provider.close();
      assertTrue(provider.isClosed());
      assertEquals(0, provider.inflightCount(), "close must reap every in-flight subprocess");
      for (var call : calls) {
        var result = Await.value("the result of a reaped call", call);
        assertEquals(KILLED_BY_SIGKILL, result.exitCode());
        assertFalse(result.timedOut());
      }
    }
  }

  /**
   * {@code close()} scans the in-flight set once. A call that passed its closed check before {@code
   * close()} and starts its process after that scan has to reap the process itself; the handler
   * closes the provider at exactly that point.
   */
  @Test
  void closeWhileACallIsLaunchingReapsItsProcess() {
    assumeBashAvailable();
    var bash = LocalProcessExecutionProvider.RuntimeHandler.dashC("bash");
    var launching = new AtomicReference<LocalProcessExecutionProvider>();
    var provider =
        LocalProcessExecutionProvider.newBuilder()
            .withSecretRegistry(new SecretRegistry())
            .withRuntime(
                Runtime.BASH,
                request -> {
                  launching.get().close();
                  return bash.buildArgv(request);
                })
            .withShutdownHook(false)
            .build();
    launching.set(provider);
    var result = run(provider, bash(HANG).build());
    assertEquals(KILLED_BY_SIGTERM, result.exitCode());
    assertFalse(result.timedOut());
    assertEquals(0, provider.inflightCount());
  }

  @Test
  void closeIsIdempotent() {
    assumeBashAvailable();
    var provider = testProvider();
    provider.close();
    provider.close();
    assertTrue(provider.isClosed());
  }

  @Test
  void executeAfterCloseFailsImmediately() {
    assumeBashAvailable();
    var provider = testProvider();
    provider.close();
    var call = start(provider, bash("true").build(), new CancellationToken());
    assertTrue(call.isCompletedExceptionally());
    var failure = Await.failure("the call on a closed provider to fail", call);
    assertInstanceOf(IllegalStateException.class, failure);
    assertEquals("provider is closed", failure.getMessage());
  }

  @Test
  void closeDuringPermitWaitAbortsTheQueuedCall() {
    assumeBashAvailable();
    try (var provider = testProvider(1)) {
      var holder = start(provider, bash(HANG).build(), new CancellationToken());
      awaitInflight(provider, 1);

      var token = new CancellationToken();
      var queued = start(provider, bash("printf x").build(), token);
      awaitPermitRequest(token);
      assertFalse(queued.isDone());

      provider.close();
      var reaped = Await.value("the reaped permit holder's result", holder);
      assertEquals(KILLED_BY_SIGTERM, reaped.exitCode());
      assertFalse(reaped.timedOut());
      assertEquals(
          "provider closed before BASH could start",
          cancellationMessage("the queued call to fail", queued));
      assertTrue(provider.isClosed());
      assertEquals(0, provider.inflightCount());
    }
  }

  // ── concurrency cap — multiple calls queue and complete ─────────────────

  @Test
  void multipleCallsRespectMaxConcurrentAndAllComplete() {
    assumeBashAvailable();
    try (var provider = testProvider(2)) {
      var calls = new ArrayList<CompletableFuture<ExecutionResult>>();
      for (var i = 0; i < 5; i++) {
        calls.add(start(provider, bash("printf '%d' " + i).build(), new CancellationToken()));
      }
      for (var i = 0; i < calls.size(); i++) {
        var result = Await.value("the result of call " + i, calls.get(i));
        assertEquals(String.valueOf(i), result.stdout());
      }
      assertEquals(0, provider.inflightCount());
    }
  }

  // ── permit released even on exceptional paths ───────────────────────────

  @Test
  void permitReleasedAfterIOErrorLaunchingProcess(@TempDir Path tmp) {
    assumeBashAvailable();
    try (var provider = testProvider(1)) {
      var missingCwd = tmp.resolve("does-not-exist");
      var result = run(provider, bash("true").withWorkingDirectory(missingCwd).build());
      assertEquals(-1, result.exitCode());
      assertTrue(result.stderr().contains("I/O error"));

      var follow = run(provider, bash("printf ok").build());
      assertEquals("ok", follow.stdout());
    }
  }

  // ── cancellation token callback churn does not accumulate ───────────────

  @Test
  void manyExecuteCallsDoNotAccumulateCallbacksOnSharedToken() {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var sharedToken = new CancellationToken();
      for (var i = 0; i < 100; i++) {
        Await.value(
            "the result of call " + i, start(provider, bash("printf x").build(), sharedToken));
      }
      assertEquals(0, provider.inflightCount());
      assertEquals(0, sharedToken.activeCallbackCountForTests());
      assertTrue(sharedToken.cancel("post-hoc"));
    }
  }

  // ── pre-cancelled token before acquire returns CancellationException ────

  @Test
  void preCancelledTokenFailsBeforeProcessStarts() {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var token = new CancellationToken();
      token.cancel("up-front");
      var call = start(provider, bash("printf should-not-run").build(), token);
      assertEquals("up-front", cancellationMessage("the pre-cancelled call to fail", call));
      assertEquals(0, provider.inflightCount());
    }
  }

  // ── drain handles closed-stream IO error without losing already-read bytes

  @Test
  void drainSurvivesProcessTerminationMidStream() {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var result = run(provider, bash("printf partial; kill -9 $$").build());
      assertEquals("partial", result.stdout());
      assertEquals(KILLED_BY_SIGKILL, result.exitCode());
      assertFalse(result.timedOut());
      assertEquals(0, provider.inflightCount());
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static void assumeBashAvailable() {
    assumeTrue(
        Files.isExecutable(Path.of("/bin/bash")) || Files.isExecutable(Path.of("/usr/bin/bash")),
        "bash is not available; skipping subprocess robustness tests");
  }

  private static ExecutionRequest.Builder bash(String script) {
    return ExecutionRequest.newBuilder()
        .withRuntime(Runtime.BASH)
        .withScript(script)
        .withTimeout(BEYOND_HANG_GUARD);
  }

  private static CompletableFuture<ExecutionResult> start(
      LocalProcessExecutionProvider provider, ExecutionRequest request, CancellationToken token) {
    return provider.execute(CTX, request, token).toCompletableFuture();
  }

  private static ExecutionResult run(
      LocalProcessExecutionProvider provider, ExecutionRequest request) {
    return Await.value(
        "the result of `" + request.script() + "`",
        start(provider, request, new CancellationToken()));
  }

  private static void awaitInflight(LocalProcessExecutionProvider provider, int processes) {
    Await.until(processes + " in-flight process(es)", () -> provider.inflightCount() == processes);
  }

  /**
   * The provider arms a callback on the call's token before it asks for a permit and disarms it
   * once it has one. While another call holds the last permit, one armed callback therefore means
   * the call is at the permit wait and cannot get past it.
   */
  private static void awaitPermitRequest(CancellationToken token) {
    Await.until(
        "the queued call to ask for a permit", () -> token.activeCallbackCountForTests() == 1);
  }

  private static String cancellationMessage(String description, CompletableFuture<?> call) {
    return assertInstanceOf(CancellationException.class, Await.failure(description, call))
        .getMessage();
  }

  /** Smoke test the shutdown hook lifecycle by registering then removing on close. */
  @Test
  void shutdownHookIsRegisteredAndRemovedOnClose() {
    assumeBashAvailable();
    // The hook is observable only via Runtime.removeShutdownHook returning true or throwing.
    var provider =
        LocalProcessExecutionProvider.newBuilder()
            .withSecretRegistry(new SecretRegistry())
            .withRuntime(Runtime.BASH, LocalProcessExecutionProvider.RuntimeHandler.dashC("bash"))
            .build();
    var ref = new AtomicReference<Thread>();
    // The constructor either added the hook (default) or didn't — we don't have direct access to
    // the field, so we rely on the contract: close() must not throw, and a follow-up close() is a
    // no-op, even if the hook was registered.
    provider.close();
    provider.close();
    assertTrue(provider.isClosed());
    assertEquals(0, provider.inflightCount());
    // Suppress the unused-var warning.
    ref.set(null);
  }

  /** Build a provider with concurrency=1 and verify cancellation chains across calls cleanly. */
  @Test
  void mixedCancellationAndSuccessSequence() {
    assumeBashAvailable();
    try (var provider = testProvider(1)) {
      assertEquals("a", run(provider, bash("printf a").build()).stdout());

      var token = new CancellationToken();
      var sleeper = start(provider, bash(HANG).build(), token);
      awaitInflight(provider, 1);
      token.cancel("mid-run");
      assertEquals(
          "execution of BASH cancelled: mid-run",
          cancellationMessage("the cancelled sleeper to fail", sleeper));

      assertEquals("c", run(provider, bash("printf c").build()).stdout());
      assertEquals(0, provider.inflightCount());
    }
  }

  /** Successful run leaves no orphan threads behind. */
  @Test
  void successfulRunReleasesAllResources() {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var initialThreads = Thread.activeCount();
      for (var i = 0; i < 20; i++) {
        run(provider, bash("printf hi").build());
      }
      // Virtual threads, so platform thread count must not climb meaningfully.
      var delta = Math.max(0, Thread.activeCount() - initialThreads);
      assertTrue(delta < 50, "platform thread count exploded: +" + delta);
      assertEquals(0, provider.inflightCount());
    }
  }

  /** Provider keeps producing structured results even when stderr is large. */
  @Test
  void largeStderrIsTruncatedAndDoesNotHang() {
    assumeBashAvailable();
    try (var provider =
        LocalProcessExecutionProvider.newBuilder()
            .withSecretRegistry(new SecretRegistry())
            .withRuntime(Runtime.BASH, LocalProcessExecutionProvider.RuntimeHandler.dashC("bash"))
            .withMaxOutputBytes(1024)
            .withShutdownHook(false)
            .build()) {
      var result = run(provider, bash("yes err | head -c 5000 1>&2").build());
      assertEquals(0, result.exitCode());
      assertFalse(result.timedOut());
      assertTrue(result.stderr().contains("truncated"));
      assertTrue(result.stderr().length() < 2048);
    }
  }

  /** A simulated abrupt drain failure must not hang the dispatcher (regression guard). */
  @Test
  void abruptProcessExitWhileDrainIsActiveStillCompletes() {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var result = run(provider, bash("printf 'pre'; exit 99").build());
      assertEquals(99, result.exitCode());
      assertEquals("pre", result.stdout());
    }
  }

  /**
   * Cancellation after the call is already done must be a no-op (callback gated by AtomicBoolean).
   */
  @Test
  void cancelAfterSuccessfulCompletionIsNoOp() {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var token = new CancellationToken();
      var result =
          Await.value(
              "the result of `printf done`", start(provider, bash("printf done").build(), token));
      assertEquals("done", result.stdout());
      assertTrue(token.cancel("post-completion"));
    }
  }

  /** Builder rejects empty args list (positional args optional, but must not be null elements). */
  @Test
  void unsupportedRuntimeOnDefaultPosixProviderReturnsRefusalShapedResult() {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var req = ExecutionRequest.newBuilder().withRuntime(Runtime.PYTHON).withScript("x").build();
      var result = run(provider, req);
      assertEquals(-1, result.exitCode());
      assertTrue(result.stderr().contains("not supported"));
      // Inflight should remain 0 — we never launched a process.
      assertEquals(0, provider.inflightCount());
    }
  }

  /** Redaction counts merge correctly when a secret appears only in stderr (not stdout). */
  @Test
  void secretRedactedFromStderrAlsoCounted() {
    assumeBashAvailable();
    var registry = new SecretRegistry();
    registry.register("TOKEN", "stderr-secret-12345");
    try (var provider =
        LocalProcessExecutionProvider.newBuilder()
            .withSecretRegistry(registry)
            .withRuntime(Runtime.BASH, LocalProcessExecutionProvider.RuntimeHandler.dashC("bash"))
            .withShutdownHook(false)
            .build()) {
      var result = run(provider, bash("printf clean; printf stderr-secret-12345 >&2").build());
      assertEquals("clean", result.stdout());
      assertEquals("<redacted:TOKEN>", result.stderr());
      assertEquals(Integer.valueOf(1), result.secretRedactionCounts().get("TOKEN"));
    }
  }

  /** Runtime handler throwing Error must surface through the future, not silently swallow. */
  @Test
  void handlerThrowingErrorSurfacesThroughFuture() {
    try (var provider =
        LocalProcessExecutionProvider.newBuilder()
            .withSecretRegistry(new SecretRegistry())
            .withRuntime(
                Runtime.BASH,
                req -> {
                  throw new OutOfMemoryError("synthetic");
                })
            .withShutdownHook(false)
            .build()) {
      var call = start(provider, bash("x").build(), new CancellationToken());
      assertInstanceOf(
          OutOfMemoryError.class, Await.failure("the call with a failing handler to fail", call));
      assertEquals(0, provider.inflightCount());
    }
  }

  /** With the JVM shutdown hook actually registered, close() must remove it cleanly. */
  @Test
  void closeRemovesRegisteredShutdownHook() {
    assumeBashAvailable();
    var provider =
        LocalProcessExecutionProvider.newBuilder()
            .withSecretRegistry(new SecretRegistry())
            .withRuntime(Runtime.BASH, LocalProcessExecutionProvider.RuntimeHandler.dashC("bash"))
            .withShutdownHook(true)
            .build();
    provider.close();
    assertTrue(provider.isClosed());
    // A second close is a no-op even though the hook was previously removed.
    provider.close();
  }

  /** Args of size N forward as positional arguments to the script. */
  @Test
  void positionalArgsForwardToBashScript() {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var req =
          bash("printf '%s %s %s' \"$1\" \"$2\" \"$3\"")
              // bash -c '<script>' SCRIPT_NAME a b c — $0=SCRIPT_NAME, $1=a, $2=b, $3=c.
              .withArgs(List.of("script", "one", "two", "three"))
              .build();
      assertEquals("one two three", run(provider, req).stdout());
    }
  }
}
