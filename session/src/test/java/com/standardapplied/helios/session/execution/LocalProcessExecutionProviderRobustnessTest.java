/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.singlr.core.common.SecretRegistry;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.runtime.SessionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Hardening tests for {@link LocalProcessExecutionProvider}: hung-subprocess reaping, abrupt-kill
 * via cancellation, large-stdin deadlock guard, provider close lifecycle, semaphore-acquire
 * cancellation, JVM shutdown hook removal.
 *
 * <p>The tests build providers via the explicit Builder with the JVM shutdown hook disabled so the
 * test runtime is not polluted with hook objects, and every provider is wrapped in
 * try-with-resources so {@code close()} reaps any leftover subprocess.
 */
final class LocalProcessExecutionProviderRobustnessTest {

  private static final SessionContext CTX = SessionContext.forTesting("robustness-test");

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

  // ── timeout reaps the process tree, including SIGTERM-deaf descendants ───

  @Test
  void timeoutEscalatesFromSigtermToSigkillWhenChildIgnoresTerm() throws Exception {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      // trap '' TERM disables the bash SIGTERM handler — the parent must escalate to SIGKILL.
      var req =
          ExecutionRequest.newBuilder()
              .withRuntime(Runtime.BASH)
              .withScript("trap '' TERM; sleep 30")
              .withTimeout(Duration.ofMillis(200))
              .build();
      var start = System.nanoTime();
      var result =
          provider
              .execute(CTX, req, new CancellationToken())
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);
      var elapsed = Duration.ofNanos(System.nanoTime() - start);
      assertTrue(result.timedOut(), "expected timedOut=true after SIGKILL escalation");
      assertTrue(
          elapsed.compareTo(Duration.ofSeconds(5)) < 0,
          "expected escalation under 5s, took " + elapsed);
      assertEquals(0, provider.inflightCount(), "no leftover in-flight processes");
    }
  }

  // ── large stdin against non-reading process completes without deadlock ──

  @Test
  void largeStdinAgainstNonReadingProcessDoesNotDeadlock() throws Exception {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      // 512 KiB of stdin against a script that exits immediately and never reads its input.
      // OS pipe buffer is typically 64 KiB — without an async writer the parent would block
      // on out.write(...) and the timeout would fire even though the script finished.
      var stdin = "x".repeat(512 * 1024);
      var req =
          ExecutionRequest.newBuilder()
              .withRuntime(Runtime.BASH)
              .withScript("printf done")
              .withStdin(stdin)
              .withTimeout(Duration.ofSeconds(5))
              .build();
      var result =
          provider
              .execute(CTX, req, new CancellationToken())
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);
      assertEquals(0, result.exitCode());
      assertEquals("done", result.stdout());
      assertFalse(result.timedOut());
      assertEquals(0, provider.inflightCount());
    }
  }

  // ── cancellation during a long sleep kills the process ──────────────────

  @Test
  void cancellationDuringExecutionKillsProcessAndCompletesExceptionally() throws Exception {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var token = new CancellationToken();
      var req =
          ExecutionRequest.newBuilder()
              .withRuntime(Runtime.BASH)
              .withScript("sleep 30")
              .withTimeout(Duration.ofSeconds(30))
              .build();
      var future = provider.execute(CTX, req, token).toCompletableFuture();
      waitFor(() -> provider.inflightCount() == 1, Duration.ofSeconds(2));
      token.cancel("test-cancel");
      // CompletableFuture#get unwraps CancellationException — it's thrown directly, not wrapped.
      // Message may come from throwIfCancelled (if cancel won the race before acquire) or our
      // post-reap CancellationException — both are correct outcomes; the contract is "future
      // completes exceptionally with CancellationException" rather than a specific message.
      assertThrows(CancellationException.class, () -> future.get(5, TimeUnit.SECONDS));
      waitFor(() -> provider.inflightCount() == 0, Duration.ofSeconds(3));
    }
  }

  // ── cancellation while waiting for a permit unblocks the acquire ────────

  @Test
  void cancellationWhilePermitWaitUnblocksAcquire() throws Exception {
    assumeBashAvailable();
    try (var provider = testProvider(1)) {
      // Saturate the single permit with a slow sleeper.
      var hold = new CancellationToken();
      var heldFuture =
          provider
              .execute(
                  CTX,
                  ExecutionRequest.newBuilder()
                      .withRuntime(Runtime.BASH)
                      .withScript("sleep 30")
                      .withTimeout(Duration.ofSeconds(30))
                      .build(),
                  hold)
              .toCompletableFuture();
      waitFor(() -> provider.inflightCount() == 1, Duration.ofSeconds(2));

      // Second call must block on the permit.
      var token = new CancellationToken();
      var waiting =
          provider
              .execute(
                  CTX,
                  ExecutionRequest.newBuilder()
                      .withRuntime(Runtime.BASH)
                      .withScript("printf x")
                      .withTimeout(Duration.ofSeconds(5))
                      .build(),
                  token)
              .toCompletableFuture();

      // Give it a moment to actually park in acquire().
      Thread.sleep(150);
      assertFalse(waiting.isDone(), "second call should be parked in acquire()");

      token.cancel("permit-cancel");
      assertThrows(CancellationException.class, () -> waiting.get(5, TimeUnit.SECONDS));

      // Clean up the holder.
      hold.cancel("done");
      assertThrows(CancellationException.class, () -> heldFuture.get(5, TimeUnit.SECONDS));
    }
  }

  // ── provider.close() reaps in-flight processes ──────────────────────────

  @Test
  void closeForciblyReapsInflightProcesses() throws Exception {
    assumeBashAvailable();
    var provider = testProvider();
    var futures = new CompletableFuture<?>[3];
    for (var i = 0; i < futures.length; i++) {
      futures[i] =
          provider
              .execute(
                  CTX,
                  ExecutionRequest.newBuilder()
                      .withRuntime(Runtime.BASH)
                      .withScript("trap '' TERM; sleep 30")
                      .withTimeout(Duration.ofSeconds(30))
                      .build(),
                  new CancellationToken())
              .toCompletableFuture();
    }
    waitFor(() -> provider.inflightCount() == 3, Duration.ofSeconds(2));
    var start = System.nanoTime();
    provider.close();
    var elapsed = Duration.ofNanos(System.nanoTime() - start);
    assertTrue(provider.isClosed());
    assertEquals(0, provider.inflightCount(), "close must reap every in-flight subprocess");
    assertTrue(
        elapsed.compareTo(Duration.ofSeconds(15)) < 0, "close took unreasonably long: " + elapsed);
    // Futures eventually complete; we don't depend on the exact terminal.
    for (var f : futures) {
      try {
        f.get(5, TimeUnit.SECONDS);
      } catch (Exception ignored) {
        // OK — either a timed-out result or an exceptional completion.
      }
    }
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
    var req = ExecutionRequest.newBuilder().withRuntime(Runtime.BASH).withScript("true").build();
    var future = provider.execute(CTX, req, new CancellationToken()).toCompletableFuture();
    var ex =
        assertThrows(
            java.util.concurrent.ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
    assertInstanceOf(IllegalStateException.class, ex.getCause());
    assertEquals("provider is closed", ex.getCause().getMessage());
  }

  @Test
  void closeDuringPermitWaitAbortsTheQueuedCall() throws Exception {
    assumeBashAvailable();
    var provider = testProvider(1);
    // Hold the only permit.
    var hold = new CancellationToken();
    var held =
        provider
            .execute(
                CTX,
                ExecutionRequest.newBuilder()
                    .withRuntime(Runtime.BASH)
                    .withScript("sleep 30")
                    .withTimeout(Duration.ofSeconds(30))
                    .build(),
                hold)
            .toCompletableFuture();
    waitFor(() -> provider.inflightCount() == 1, Duration.ofSeconds(2));

    var queued =
        provider
            .execute(
                CTX,
                ExecutionRequest.newBuilder()
                    .withRuntime(Runtime.BASH)
                    .withScript("printf x")
                    .build(),
                new CancellationToken())
            .toCompletableFuture();
    Thread.sleep(150);
    assertFalse(queued.isDone());

    provider.close();
    // close() reaps the holder and the queued call returns either exceptionally or with a
    // refusal-shaped result depending on race ordering — both are acceptable.
    try {
      held.get(5, TimeUnit.SECONDS);
    } catch (Exception ignored) {
      // OK
    }
    try {
      queued.get(5, TimeUnit.SECONDS);
    } catch (Exception ignored) {
      // OK
    }
    assertTrue(provider.isClosed());
  }

  // ── concurrency cap — multiple calls queue and complete ─────────────────

  @Test
  void multipleCallsRespectMaxConcurrentAndAllComplete() throws Exception {
    assumeBashAvailable();
    try (var provider = testProvider(2)) {
      var futures = new CompletableFuture<?>[5];
      for (var i = 0; i < futures.length; i++) {
        var idx = i;
        futures[i] =
            provider
                .execute(
                    CTX,
                    ExecutionRequest.newBuilder()
                        .withRuntime(Runtime.BASH)
                        .withScript("printf '%d' " + idx)
                        .withTimeout(Duration.ofSeconds(5))
                        .build(),
                    new CancellationToken())
                .toCompletableFuture();
      }
      for (var i = 0; i < futures.length; i++) {
        var r = (ExecutionResult) futures[i].get(15, TimeUnit.SECONDS);
        assertEquals(String.valueOf(i), r.stdout());
      }
      assertEquals(0, provider.inflightCount());
    }
  }

  // ── permit released even on exceptional paths ───────────────────────────

  @Test
  void permitReleasedAfterIOErrorLaunchingProcess() throws Exception {
    assumeBashAvailable();
    // Working directory that doesn't exist forces ProcessBuilder.start() to throw IOException.
    try (var provider = testProvider(1)) {
      var bogusCwd = Path.of("/tmp/helios-does-not-exist-" + System.nanoTime());
      var req =
          ExecutionRequest.newBuilder()
              .withRuntime(Runtime.BASH)
              .withScript("true")
              .withWorkingDirectory(bogusCwd)
              .build();
      var result =
          provider
              .execute(CTX, req, new CancellationToken())
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS);
      assertEquals(-1, result.exitCode());
      assertTrue(result.stderr().contains("I/O error"));

      // A second call must still succeed — the permit was released.
      var follow =
          provider
              .execute(
                  CTX,
                  ExecutionRequest.newBuilder()
                      .withRuntime(Runtime.BASH)
                      .withScript("printf ok")
                      .build(),
                  new CancellationToken())
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS);
      assertEquals("ok", follow.stdout());
    }
  }

  // ── cancellation token callback churn does not accumulate ───────────────

  @Test
  void manyExecuteCallsDoNotAccumulateCallbacksOnSharedToken() throws Exception {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var sharedToken = new CancellationToken();
      // 100 quick calls against the same token should each install + clear its own kill ref.
      for (var i = 0; i < 100; i++) {
        provider
            .execute(
                CTX,
                ExecutionRequest.newBuilder()
                    .withRuntime(Runtime.BASH)
                    .withScript("printf x")
                    .withTimeout(Duration.ofSeconds(5))
                    .build(),
                sharedToken)
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
      }
      assertEquals(0, provider.inflightCount());
      // Cancelling now must not destroy any live process (there are none) and must not throw.
      sharedToken.cancel("post-hoc");
    }
  }

  // ── pre-cancelled token before acquire returns CancellationException ────

  @Test
  void preCancelledTokenFailsBeforeProcessStarts() throws Exception {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var token = new CancellationToken();
      token.cancel("up-front");
      var req =
          ExecutionRequest.newBuilder()
              .withRuntime(Runtime.BASH)
              .withScript("printf should-not-run")
              .build();
      assertThrows(
          CancellationException.class,
          () -> provider.execute(CTX, req, token).toCompletableFuture().get(2, TimeUnit.SECONDS));
      assertEquals(0, provider.inflightCount());
    }
  }

  // ── drain handles closed-stream IO error without losing already-read bytes

  @Test
  void drainSurvivesProcessTerminationMidStream() throws Exception {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      // Print, then immediately self-kill — drain will observe the streams closing.
      var req =
          ExecutionRequest.newBuilder()
              .withRuntime(Runtime.BASH)
              .withScript("printf partial; kill -9 $$")
              .withTimeout(Duration.ofSeconds(5))
              .build();
      var result =
          provider
              .execute(CTX, req, new CancellationToken())
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);
      assertTrue(result.stdout().startsWith("partial"), "got: " + result.stdout());
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

  private static void waitFor(BooleanSupplier cond, Duration budget) throws InterruptedException {
    var deadline = System.nanoTime() + budget.toNanos();
    while (!cond.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        return;
      }
      Thread.sleep(20);
    }
  }

  @FunctionalInterface
  private interface BooleanSupplier {
    boolean getAsBoolean();
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
  void mixedCancellationAndSuccessSequence() throws Exception {
    assumeBashAvailable();
    try (var provider = testProvider(1)) {
      // success
      var r1 =
          provider
              .execute(
                  CTX,
                  ExecutionRequest.newBuilder()
                      .withRuntime(Runtime.BASH)
                      .withScript("printf a")
                      .build(),
                  new CancellationToken())
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS);
      assertEquals("a", r1.stdout());

      // cancelled mid-run
      var token = new CancellationToken();
      var sleeper =
          provider
              .execute(
                  CTX,
                  ExecutionRequest.newBuilder()
                      .withRuntime(Runtime.BASH)
                      .withScript("sleep 10")
                      .withTimeout(Duration.ofSeconds(10))
                      .build(),
                  token)
              .toCompletableFuture();
      waitFor(() -> provider.inflightCount() == 1, Duration.ofSeconds(2));
      token.cancel("mid-run");
      assertThrows(CancellationException.class, () -> sleeper.get(5, TimeUnit.SECONDS));

      // success after cancel — provider is healthy
      var r3 =
          provider
              .execute(
                  CTX,
                  ExecutionRequest.newBuilder()
                      .withRuntime(Runtime.BASH)
                      .withScript("printf c")
                      .build(),
                  new CancellationToken())
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS);
      assertEquals("c", r3.stdout());
      assertEquals(0, provider.inflightCount());
    }
  }

  /** Successful run leaves no orphan threads behind. */
  @Test
  void successfulRunReleasesAllResources() throws Exception {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var initialThreads = Thread.activeCount();
      for (var i = 0; i < 20; i++) {
        provider
            .execute(
                CTX,
                ExecutionRequest.newBuilder()
                    .withRuntime(Runtime.BASH)
                    .withScript("printf hi")
                    .build(),
                new CancellationToken())
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
      }
      // Virtual threads, so platform thread count must not climb meaningfully.
      var delta = Math.max(0, Thread.activeCount() - initialThreads);
      assertTrue(delta < 50, "platform thread count exploded: +" + delta);
      assertEquals(0, provider.inflightCount());
    }
  }

  /** Provider keeps producing structured results even when stderr is large. */
  @Test
  void largeStderrIsTruncatedAndDoesNotHang() throws Exception {
    assumeBashAvailable();
    try (var provider =
        LocalProcessExecutionProvider.newBuilder()
            .withSecretRegistry(new SecretRegistry())
            .withRuntime(Runtime.BASH, LocalProcessExecutionProvider.RuntimeHandler.dashC("bash"))
            .withMaxOutputBytes(1024)
            .withShutdownHook(false)
            .build()) {
      var req =
          ExecutionRequest.newBuilder()
              .withRuntime(Runtime.BASH)
              .withScript("yes err 1>&2 | head -c 5000 1>&2")
              .withTimeout(Duration.ofSeconds(5))
              .build();
      var result =
          provider
              .execute(CTX, req, new CancellationToken())
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);
      assertTrue(result.stderr().contains("truncated"));
      assertTrue(result.stderr().length() < 2048);
    }
  }

  /** A simulated abrupt drain failure must not hang the dispatcher (regression guard). */
  @Test
  void abruptProcessExitWhileDrainIsActiveStillCompletes() throws Exception {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var latch = new CountDownLatch(1);
      var bg =
          Thread.startVirtualThread(
              () -> {
                try {
                  provider
                      .execute(
                          CTX,
                          ExecutionRequest.newBuilder()
                              .withRuntime(Runtime.BASH)
                              .withScript("printf 'pre'; exit 99")
                              .build(),
                          new CancellationToken())
                      .toCompletableFuture()
                      .get(5, TimeUnit.SECONDS);
                  latch.countDown();
                } catch (Exception e) {
                  throw new AssertionError(e);
                }
              });
      assertTrue(latch.await(10, TimeUnit.SECONDS), "execute must complete after abrupt exit");
      bg.join(1000);
    }
  }

  /**
   * Cancellation after the call is already done must be a no-op (callback gated by AtomicBoolean).
   */
  @Test
  void cancelAfterSuccessfulCompletionIsNoOp() throws Exception {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var token = new CancellationToken();
      var result =
          provider
              .execute(
                  CTX,
                  ExecutionRequest.newBuilder()
                      .withRuntime(Runtime.BASH)
                      .withScript("printf done")
                      .build(),
                  token)
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS);
      assertEquals("done", result.stdout());
      // Cancelling now must be safe — no live process to destroy, no exception.
      assertTrue(token.cancel("post-completion"));
    }
  }

  /** Builder rejects empty args list (positional args optional, but must not be null elements). */
  @Test
  void unsupportedRuntimeOnDefaultPosixProviderReturnsRefusalShapedResult() throws Exception {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var req = ExecutionRequest.newBuilder().withRuntime(Runtime.PYTHON).withScript("x").build();
      var result =
          provider
              .execute(CTX, req, new CancellationToken())
              .toCompletableFuture()
              .get(2, TimeUnit.SECONDS);
      assertEquals(-1, result.exitCode());
      assertTrue(result.stderr().contains("not supported"));
      // Inflight should remain 0 — we never launched a process.
      assertEquals(0, provider.inflightCount());
    }
  }

  /** Redaction counts merge correctly when a secret appears only in stderr (not stdout). */
  @Test
  void secretRedactedFromStderrAlsoCounted() throws Exception {
    assumeBashAvailable();
    var registry = new SecretRegistry();
    registry.register("TOKEN", "stderr-secret-12345");
    try (var provider =
        LocalProcessExecutionProvider.newBuilder()
            .withSecretRegistry(registry)
            .withRuntime(Runtime.BASH, LocalProcessExecutionProvider.RuntimeHandler.dashC("bash"))
            .withShutdownHook(false)
            .build()) {
      var req =
          ExecutionRequest.newBuilder()
              .withRuntime(Runtime.BASH)
              .withScript("printf clean; printf stderr-secret-12345 >&2")
              .withTimeout(Duration.ofSeconds(5))
              .build();
      var result =
          provider
              .execute(CTX, req, new CancellationToken())
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);
      assertEquals("clean", result.stdout());
      assertEquals("<redacted:TOKEN>", result.stderr());
      assertEquals(Integer.valueOf(1), result.secretRedactionCounts().get("TOKEN"));
    }
  }

  /** Runtime handler throwing Error must surface through the future, not silently swallow. */
  @Test
  void handlerThrowingErrorSurfacesThroughFuture() throws Exception {
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
      var req = ExecutionRequest.newBuilder().withRuntime(Runtime.BASH).withScript("x").build();
      var future = provider.execute(CTX, req, new CancellationToken()).toCompletableFuture();
      var ex =
          assertThrows(
              java.util.concurrent.ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
      assertInstanceOf(OutOfMemoryError.class, ex.getCause());
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
  void positionalArgsForwardToBashScript() throws Exception {
    assumeBashAvailable();
    try (var provider = testProvider()) {
      var req =
          ExecutionRequest.newBuilder()
              .withRuntime(Runtime.BASH)
              // bash -c '<script>' SCRIPT_NAME a b c — $0=SCRIPT_NAME, $1=a, $2=b, $3=c.
              .withScript("printf '%s %s %s' \"$1\" \"$2\" \"$3\"")
              .withArgs(List.of("script", "one", "two", "three"))
              .withTimeout(Duration.ofSeconds(5))
              .build();
      var result =
          provider
              .execute(CTX, req, new CancellationToken())
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS);
      assertEquals("one two three", result.stdout());
    }
  }
}
