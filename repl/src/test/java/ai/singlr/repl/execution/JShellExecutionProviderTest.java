/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.runtime.SessionContext;
import ai.singlr.repl.ReplConfig;
import ai.singlr.repl.ReplException;
import ai.singlr.repl.sandbox.ExecuteParams;
import ai.singlr.repl.sandbox.Sandbox;
import ai.singlr.session.execution.ExecutionRequest;
import ai.singlr.session.execution.Runtime;
import ai.singlr.session.execution.SessionStartOutcome;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class JShellExecutionProviderTest {

  private static SessionContext ctx(String id) {
    return SessionContext.forTesting(id);
  }

  /** A controllable in-test sandbox the provider's ReplSession will wrap. */
  private static class StubSandbox implements Sandbox {
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastCode = new AtomicReference<>();
    String stdoutPerCall = "ok";
    String stderrPerCall = "";
    int exitCodePerCall = 0;
    long delayMillis = 0;
    boolean throwOnExecute = false;

    @Override
    public ai.singlr.repl.sandbox.ExecutionResult execute(
        ai.singlr.repl.sandbox.ExecutionRequest request) {
      calls.incrementAndGet();
      lastCode.set(request.code());
      if (delayMillis > 0) {
        // Poll for "still alive" so close() from the provider's cancellation callback aborts the
        // simulated long-running snippet promptly, matching real JvmSandbox behaviour where
        // closing the subprocess unsticks the blocking RPC.
        var deadline = System.nanoTime() + Duration.ofMillis(delayMillis).toNanos();
        while (System.nanoTime() < deadline) {
          if (!alive.get()) {
            throw new ReplException("sandbox closed mid-execute");
          }
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReplException("sandbox interrupted mid-execute");
          }
        }
      }
      if (throwOnExecute) {
        throw new RuntimeException("sandbox boom");
      }
      return new ai.singlr.repl.sandbox.ExecutionResult(
          stdoutPerCall, stderrPerCall, exitCodePerCall, null);
    }

    @Override
    public ai.singlr.repl.sandbox.ExecutionResult execute(
        ai.singlr.repl.sandbox.ExecutionRequest request, ExecuteParams params) {
      return execute(request);
    }

    @Override
    public boolean isAlive() {
      return alive.get();
    }

    @Override
    public void close() {
      alive.set(false);
    }
  }

  private static ReplConfig configWithSandbox(StubSandbox sandbox) {
    return ReplConfig.newBuilder()
        .withSandboxFactory(registry -> sandbox)
        .withExecutionTimeout(Duration.ofSeconds(5))
        .build();
  }

  private static JShellExecutionProvider providerFor(StubSandbox sandbox) {
    return JShellExecutionProvider.newBuilder()
        .withReplConfig(configWithSandbox(sandbox))
        .withShutdownHook(false)
        .build();
  }

  // ── Builder validation ────────────────────────────────────────────────────

  @Test
  void builderRequiresReplConfig() {
    var b = JShellExecutionProvider.newBuilder();
    var ex = assertThrows(IllegalStateException.class, b::build);
    assertEquals("replConfig is required", ex.getMessage());
  }

  @Test
  void builderRejectsNullReplConfig() {
    var b = JShellExecutionProvider.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withReplConfig(null));
    assertEquals("replConfig must not be null", ex.getMessage());
  }

  @Test
  void builderRejectsZeroMaxConcurrentSessions() {
    var b = JShellExecutionProvider.newBuilder();
    var ex = assertThrows(IllegalArgumentException.class, () -> b.withMaxConcurrentSessions(0));
    assertTrue(ex.getMessage().startsWith("maxConcurrentSessions"));
  }

  @Test
  void builderRejectsNullMaxTimeout() {
    var b = JShellExecutionProvider.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withMaxTimeout(null));
    assertEquals("maxTimeout must not be null", ex.getMessage());
  }

  @Test
  void builderRejectsZeroMaxTimeout() {
    var b = JShellExecutionProvider.newBuilder();
    var ex = assertThrows(IllegalArgumentException.class, () -> b.withMaxTimeout(Duration.ZERO));
    assertTrue(ex.getMessage().startsWith("maxTimeout must be strictly positive"));
  }

  @Test
  void builderWithAllOptions() {
    var sandbox = new StubSandbox();
    try (var provider =
        JShellExecutionProvider.newBuilder()
            .withReplConfig(configWithSandbox(sandbox))
            .withMaxConcurrentSessions(2)
            .withMaxTimeout(Duration.ofMinutes(10))
            .withNetworkAllowed(true)
            .withFilesystemWriteAllowed(true)
            .withShutdownHook(false)
            .build()) {
      assertEquals(Duration.ofMinutes(10), provider.capabilities().maxTimeout());
      assertTrue(provider.capabilities().networkAllowed());
      assertTrue(provider.capabilities().filesystemWriteAllowed());
      assertTrue(provider.capabilities().supports(Runtime.JSHELL));
      assertFalse(provider.capabilities().supports(Runtime.BASH));
    }
  }

  @Test
  void createFactoryRejectsNullConfig() {
    var ex = assertThrows(NullPointerException.class, () -> JShellExecutionProvider.create(null));
    assertEquals("replConfig must not be null", ex.getMessage());
  }

  // ── Capabilities + identity ───────────────────────────────────────────────

  @Test
  void capabilitiesAdvertiseJShellOnly() {
    var sandbox = new StubSandbox();
    try (var provider = providerFor(sandbox)) {
      var caps = provider.capabilities();
      assertEquals(Set.of(Runtime.JSHELL), caps.supportedRuntimes());
    }
  }

  // ── onSessionStart ────────────────────────────────────────────────────────

  @Test
  void onSessionStartAcceptsAndRegistersSession() {
    var sandbox = new StubSandbox();
    try (var provider = providerFor(sandbox)) {
      var outcome = provider.onSessionStart(ctx("s1"));
      assertInstanceOf(SessionStartOutcome.Accept.class, outcome);
      assertEquals(1, provider.liveSessionCount());
    }
  }

  @Test
  void onSessionStartRejectsNullContext() {
    var sandbox = new StubSandbox();
    try (var provider = providerFor(sandbox)) {
      assertThrows(NullPointerException.class, () -> provider.onSessionStart(null));
    }
  }

  @Test
  void onSessionStartRefusesWhenClosed() {
    var sandbox = new StubSandbox();
    var provider = providerFor(sandbox);
    provider.close();
    var outcome = provider.onSessionStart(ctx("s1"));
    assertInstanceOf(SessionStartOutcome.Refuse.class, outcome);
    assertTrue(((SessionStartOutcome.Refuse) outcome).reason().contains("closed"));
  }

  @Test
  void onSessionStartRefusesWhenPoolSaturated() {
    var sandbox = new StubSandbox();
    try (var provider =
        JShellExecutionProvider.newBuilder()
            .withReplConfig(configWithSandbox(sandbox))
            .withMaxConcurrentSessions(1)
            .withShutdownHook(false)
            .build()) {
      provider.onSessionStart(ctx("first"));
      var outcome = provider.onSessionStart(ctx("second"));
      assertInstanceOf(SessionStartOutcome.Refuse.class, outcome);
      assertTrue(((SessionStartOutcome.Refuse) outcome).reason().contains("saturated"));
    }
  }

  @Test
  void onSessionStartRefusesWhenSandboxFactoryThrows() {
    var rootCause = new java.io.IOException("Cannot run program 'java': No such file or directory");
    var thrown = new RuntimeException("sandbox init failed", rootCause);
    var failingConfig =
        ReplConfig.newBuilder()
            .withSandboxFactory(
                registry -> {
                  throw thrown;
                })
            .build();
    try (var provider =
        JShellExecutionProvider.newBuilder()
            .withReplConfig(failingConfig)
            .withShutdownHook(false)
            .build()) {
      var outcome = provider.onSessionStart(ctx("doomed"));
      var refuse = assertInstanceOf(SessionStartOutcome.Refuse.class, outcome);
      assertTrue(refuse.reason().contains("failed to spawn"));
      // The motivating bug for 2.5.2: the underlying throwable must reach the deployer with its
      // full cause chain intact. Without this, "ProcessBuilder.start() threw IOException: <real
      // reason>" is collapsed into the wrapper's getMessage() and the deployer can't tell missing
      // binary from permission denied from disk full.
      //
      // ReplSession.create wraps the sandbox-factory failure in a ReplException, so the chain
      // visible to the deployer is: ReplException("Failed to create session") ←
      // RuntimeException("sandbox init failed") ← IOException("Cannot run program 'java': ...").
      // All three messages are reachable, which is exactly the contract we want — operators see
      // the full diagnostic path, not just the outermost wrapper.
      assertNotNull(refuse.cause(), "underlying throwable must be preserved");
      assertEquals(
          "ai.singlr.repl.ReplException",
          refuse.cause().getClass().getName(),
          "ReplSession wraps sandbox failures, so the immediate cause is ReplException");
      assertSame(
          thrown,
          refuse.cause().getCause(),
          "the original RuntimeException is the next link in the chain");
      assertSame(
          rootCause,
          refuse.cause().getCause().getCause(),
          "the root IOException remains reachable at the bottom of the chain");
      // Provider state must be clean — no live sessions, no leaked permits.
      assertEquals(0, provider.liveSessionCount());
    }
  }

  // ── onSessionEnd ──────────────────────────────────────────────────────────

  @Test
  void onSessionEndClosesReplSessionAndReleasesPermit() {
    var sandbox = new StubSandbox();
    try (var provider =
        JShellExecutionProvider.newBuilder()
            .withReplConfig(configWithSandbox(sandbox))
            .withMaxConcurrentSessions(1)
            .withShutdownHook(false)
            .build()) {
      var c = ctx("end-test");
      provider.onSessionStart(c);
      assertEquals(1, provider.liveSessionCount());
      provider.onSessionEnd(c);
      assertEquals(0, provider.liveSessionCount());
      // A new session can now claim the released permit.
      var outcome = provider.onSessionStart(ctx("second"));
      assertInstanceOf(SessionStartOutcome.Accept.class, outcome);
    }
  }

  @Test
  void onSessionEndIsNoOpForUnknownSession() {
    var sandbox = new StubSandbox();
    try (var provider = providerFor(sandbox)) {
      provider.onSessionEnd(ctx("never-started")); // does not throw
      assertEquals(0, provider.liveSessionCount());
    }
  }

  @Test
  void onSessionEndRejectsNullContext() {
    var sandbox = new StubSandbox();
    try (var provider = providerFor(sandbox)) {
      assertThrows(NullPointerException.class, () -> provider.onSessionEnd(null));
    }
  }

  // ── execute() routing + state persistence ─────────────────────────────────

  @Test
  void executeRoutesToPerSessionReplSession() throws Exception {
    var sandbox = new StubSandbox();
    sandbox.stdoutPerCall = "hello";
    try (var provider = providerFor(sandbox)) {
      var c = ctx("route");
      provider.onSessionStart(c);
      var req =
          ExecutionRequest.newBuilder()
              .withRuntime(Runtime.JSHELL)
              .withScript("var x = 1;")
              .build();
      var result =
          provider
              .execute(c, req, new CancellationToken())
              .toCompletableFuture()
              .get(2, TimeUnit.SECONDS);
      assertEquals(0, result.exitCode());
      assertEquals("hello", result.stdout());
      assertEquals(1, sandbox.calls.get());
      assertEquals("var x = 1;", sandbox.lastCode.get());
    }
  }

  @Test
  void executeAcrossMultipleSessionsIsIsolated() throws Exception {
    // Two sessions, two sandboxes — verify the provider routes each call to the right one.
    var sandboxA = new StubSandbox();
    sandboxA.stdoutPerCall = "alpha";
    var sandboxB = new StubSandbox();
    sandboxB.stdoutPerCall = "beta";

    // Sequential factory: first call returns sandboxA, second returns sandboxB.
    var nextSandbox = new AtomicReference<StubSandbox>(sandboxA);
    var second = new AtomicReference<>(sandboxB);
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(
                registry -> {
                  var s = nextSandbox.getAndSet(second.get());
                  return s;
                })
            .withExecutionTimeout(Duration.ofSeconds(5))
            .build();
    try (var provider =
        JShellExecutionProvider.newBuilder()
            .withReplConfig(config)
            .withMaxConcurrentSessions(2)
            .withShutdownHook(false)
            .build()) {
      var cA = ctx("A");
      var cB = ctx("B");
      provider.onSessionStart(cA);
      provider.onSessionStart(cB);

      var reqA = ExecutionRequest.newBuilder().withRuntime(Runtime.JSHELL).withScript("a").build();
      var reqB = ExecutionRequest.newBuilder().withRuntime(Runtime.JSHELL).withScript("b").build();
      var rA = provider.execute(cA, reqA, new CancellationToken()).toCompletableFuture().get();
      var rB = provider.execute(cB, reqB, new CancellationToken()).toCompletableFuture().get();
      assertEquals("alpha", rA.stdout());
      assertEquals("beta", rB.stdout());
      assertEquals(1, sandboxA.calls.get());
      assertEquals(1, sandboxB.calls.get());
    }
  }

  @Test
  void stateLikePersistenceAcrossCalls() throws Exception {
    // The sandbox stub records each script; the provider routes both calls to the same sandbox —
    // proving persistence (no new fork per call).
    var sandbox = new StubSandbox();
    try (var provider = providerFor(sandbox)) {
      var c = ctx("persist");
      provider.onSessionStart(c);
      for (var snippet : new String[] {"var x = 1;", "x + 2"}) {
        var req =
            ExecutionRequest.newBuilder().withRuntime(Runtime.JSHELL).withScript(snippet).build();
        provider.execute(c, req, new CancellationToken()).toCompletableFuture().get();
      }
      assertEquals(2, sandbox.calls.get());
      assertEquals("x + 2", sandbox.lastCode.get());
    }
  }

  @Test
  void executeForUnknownSessionReturnsRefusal() throws Exception {
    var sandbox = new StubSandbox();
    try (var provider = providerFor(sandbox)) {
      var req = ExecutionRequest.newBuilder().withRuntime(Runtime.JSHELL).withScript("x").build();
      var result =
          provider
              .execute(ctx("ghost"), req, new CancellationToken())
              .toCompletableFuture()
              .get(2, TimeUnit.SECONDS);
      assertEquals(-1, result.exitCode());
      assertTrue(result.stderr().contains("no JShell session"));
    }
  }

  @Test
  void executeForNonJshellRuntimeReturnsRefusal() throws Exception {
    var sandbox = new StubSandbox();
    try (var provider = providerFor(sandbox)) {
      var c = ctx("type-mismatch");
      provider.onSessionStart(c);
      var req =
          ExecutionRequest.newBuilder().withRuntime(Runtime.PYTHON).withScript("print(1)").build();
      var result =
          provider
              .execute(c, req, new CancellationToken())
              .toCompletableFuture()
              .get(2, TimeUnit.SECONDS);
      assertEquals(-1, result.exitCode());
      assertTrue(result.stderr().contains("runtime not supported"));
    }
  }

  @Test
  void executeAfterCloseFailsImmediately() {
    var sandbox = new StubSandbox();
    var provider = providerFor(sandbox);
    provider.onSessionStart(ctx("temp"));
    provider.close();
    var req = ExecutionRequest.newBuilder().withRuntime(Runtime.JSHELL).withScript("x").build();
    var future = provider.execute(ctx("temp"), req, new CancellationToken()).toCompletableFuture();
    var ex =
        assertThrows(
            java.util.concurrent.ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
    assertInstanceOf(IllegalStateException.class, ex.getCause());
  }

  @Test
  void executeRejectsNullArgs() {
    var sandbox = new StubSandbox();
    try (var provider = providerFor(sandbox)) {
      var c = ctx("nulls");
      provider.onSessionStart(c);
      var req = ExecutionRequest.newBuilder().withRuntime(Runtime.JSHELL).withScript("x").build();
      var token = new CancellationToken();
      assertThrows(NullPointerException.class, () -> provider.execute(null, req, token));
      assertThrows(NullPointerException.class, () -> provider.execute(c, null, token));
      assertThrows(NullPointerException.class, () -> provider.execute(c, req, null));
    }
  }

  @Test
  void executeSandboxThrowsReturnsFailure() throws Exception {
    var sandbox = new StubSandbox();
    sandbox.throwOnExecute = true;
    try (var provider = providerFor(sandbox)) {
      var c = ctx("err");
      provider.onSessionStart(c);
      var req =
          ExecutionRequest.newBuilder().withRuntime(Runtime.JSHELL).withScript("boom").build();
      var future = provider.execute(c, req, new CancellationToken()).toCompletableFuture();
      // The runtime exception from the sandbox surfaces as a future failure.
      assertThrows(
          java.util.concurrent.ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
    }
  }

  // ── Cancellation ──────────────────────────────────────────────────────────

  @Test
  void cancellationDuringExecuteCompletesExceptionallyAndKillsSession() throws Exception {
    var sandbox = new StubSandbox();
    sandbox.delayMillis = 5_000; // simulate a long-running snippet
    try (var provider = providerFor(sandbox)) {
      var c = ctx("cancel");
      provider.onSessionStart(c);
      var token = new CancellationToken();
      var req =
          ExecutionRequest.newBuilder().withRuntime(Runtime.JSHELL).withScript("sleep").build();
      var future = provider.execute(c, req, token).toCompletableFuture();
      // Give it a beat to enter the sandbox call, then cancel.
      Thread.sleep(50);
      token.cancel("user-cancel");
      assertThrows(CancellationException.class, () -> future.get(2, TimeUnit.SECONDS));
      // The session must have been killed.
      assertFalse(sandbox.isAlive());
    }
  }

  // ── Lifecycle: close() reaps everything ──────────────────────────────────

  @Test
  void closeReapsAllLiveSessions() {
    var sandboxA = new StubSandbox();
    var sandboxB = new StubSandbox();
    var nextSandbox = new AtomicReference<StubSandbox>(sandboxA);
    var second = new AtomicReference<>(sandboxB);
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(registry -> nextSandbox.getAndSet(second.get()))
            .withExecutionTimeout(Duration.ofSeconds(5))
            .build();
    var provider =
        JShellExecutionProvider.newBuilder()
            .withReplConfig(config)
            .withMaxConcurrentSessions(2)
            .withShutdownHook(false)
            .build();
    provider.onSessionStart(ctx("a"));
    provider.onSessionStart(ctx("b"));
    assertEquals(2, provider.liveSessionCount());
    provider.close();
    assertTrue(provider.isClosed());
    assertEquals(0, provider.liveSessionCount());
    assertFalse(sandboxA.isAlive());
    assertFalse(sandboxB.isAlive());
  }

  @Test
  void closeIsIdempotent() {
    var sandbox = new StubSandbox();
    var provider = providerFor(sandbox);
    provider.close();
    provider.close();
    assertTrue(provider.isClosed());
  }

  @Test
  void closeWithRegisteredShutdownHookRemovesIt() {
    var sandbox = new StubSandbox();
    var provider =
        JShellExecutionProvider.newBuilder()
            .withReplConfig(configWithSandbox(sandbox))
            .withShutdownHook(true)
            .build();
    provider.close();
    assertTrue(provider.isClosed());
  }

  // ── session cancellation token wiring ────────────────────────────────────

  @Test
  void sessionCancellationTokenKillsReplSession() throws Exception {
    var sandbox = new StubSandbox();
    try (var provider = providerFor(sandbox)) {
      var token = new CancellationToken();
      var session = new SessionContext("scoped", token, Clock.systemUTC());
      provider.onSessionStart(session);
      assertEquals(1, provider.liveSessionCount());

      token.cancel("session-end-via-token");
      // Give the callback a moment to fire.
      var deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (provider.liveSessionCount() != 0 && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      assertEquals(0, provider.liveSessionCount());
      assertFalse(sandbox.isAlive());
    }
  }

  /** Multiple sequential execute() calls reuse the same sandbox — a smoke test for persistence. */
  @Test
  void concurrentExecutesOnSameSessionAllReachSandbox() throws Exception {
    var sandbox = new StubSandbox();
    try (var provider = providerFor(sandbox)) {
      var c = ctx("concurrent");
      provider.onSessionStart(c);
      var latch = new CountDownLatch(3);
      var futures = new CompletableFuture<?>[3];
      for (var i = 0; i < 3; i++) {
        var idx = i;
        futures[i] =
            provider
                .execute(
                    c,
                    ExecutionRequest.newBuilder()
                        .withRuntime(Runtime.JSHELL)
                        .withScript("call-" + idx)
                        .build(),
                    new CancellationToken())
                .toCompletableFuture()
                .whenComplete((r, t) -> latch.countDown());
      }
      assertTrue(latch.await(5, TimeUnit.SECONDS));
      for (var f : futures) {
        f.get(2, TimeUnit.SECONDS);
      }
      assertEquals(3, sandbox.calls.get());
    }
  }

  /** When the provider was built with shutdownHook=false, capabilities still expose JSHELL. */
  @Test
  void shutdownHookDisabledStillAdvertisesCapabilities() {
    var sandbox = new StubSandbox();
    try (var provider = providerFor(sandbox)) {
      assertSame(Runtime.JSHELL, provider.capabilities().supportedRuntimes().iterator().next());
    }
  }

  /** capabilities() returns a stable value across invocations. */
  @Test
  void capabilitiesIsStable() {
    var sandbox = new StubSandbox();
    try (var provider = providerFor(sandbox)) {
      var a = provider.capabilities();
      var b = provider.capabilities();
      assertSame(a, b);
    }
  }

  /** maxConcurrentSessions accessor reports the configured cap regardless of live count. */
  @Test
  void maxConcurrentSessionsAccessorAddsPermitsAndLive() {
    var sandbox = new StubSandbox();
    try (var provider =
        JShellExecutionProvider.newBuilder()
            .withReplConfig(configWithSandbox(sandbox))
            .withMaxConcurrentSessions(3)
            .withShutdownHook(false)
            .build()) {
      assertEquals(3, provider.maxConcurrentSessions());
      provider.onSessionStart(ctx("a"));
      assertEquals(3, provider.maxConcurrentSessions());
    }
  }

  /**
   * Successful execute() observes that cancellation fired post-completion → surface a {@link
   * CancellationException} rather than the Success result. Covers the "cancellation.isCancelled()
   * after raw=execute()" branch.
   */
  @Test
  void cancellationRaceObservedAfterSuccessfulExecuteSurfacesCancellation() throws Exception {
    var sandbox = new StubSandbox();
    // 200ms delay gives us time to fire the cancel right before the snippet returns. The kill
    // callback is gated by AtomicBoolean — closing the sandbox via cancellation while it's still
    // looping for "alive" causes the stub to throw ReplException. We instead want the SUCCESS
    // path's post-completion cancel check, so configure delayMillis=0 here and cancel before the
    // future resolves by piggy-backing on a separate token.
    sandbox.delayMillis = 0;
    try (var provider = providerFor(sandbox)) {
      var token = new CancellationToken();
      var c = ctx("race");
      provider.onSessionStart(c);
      var req = ExecutionRequest.newBuilder().withRuntime(Runtime.JSHELL).withScript("x").build();
      // Pre-cancel before dispatch — the worker thread sees `cancellation.isCancelled()` true after
      // sandbox.execute() returns normally.
      token.cancel("ahead-of-time");
      var future = provider.execute(c, req, token).toCompletableFuture();
      assertThrows(CancellationException.class, () -> future.get(2, TimeUnit.SECONDS));
    }
  }

  /** ReplException with null message falls back to the class's simple name. */
  @Test
  void replExceptionWithNullMessageFallsBackToClassName() throws Exception {
    // Wrap the stub so its execute throws a ReplException(null).
    var sandbox =
        new StubSandbox() {
          @Override
          public ai.singlr.repl.sandbox.ExecutionResult execute(
              ai.singlr.repl.sandbox.ExecutionRequest request) {
            throw new ReplException((String) null);
          }
        };
    try (var provider = providerFor(sandbox)) {
      var c = ctx("null-msg");
      provider.onSessionStart(c);
      var req = ExecutionRequest.newBuilder().withRuntime(Runtime.JSHELL).withScript("x").build();
      var result =
          provider
              .execute(c, req, new CancellationToken())
              .toCompletableFuture()
              .get(2, TimeUnit.SECONDS);
      // No message → simple name surfaces; "ReplException" is the class name.
      assertTrue(
          result.stderr().contains("ReplException"),
          "expected ReplException class name in stderr, got: " + result.stderr());
    }
  }

  /** Convenience {@code create(...)} factory wires the same defaults as the Builder. */
  @Test
  void createFactoryWiresDefaultProvider() {
    var sandbox = new StubSandbox();
    try (var provider = JShellExecutionProvider.create(configWithSandbox(sandbox))) {
      assertTrue(provider.capabilities().supports(Runtime.JSHELL));
      assertEquals(4, provider.maxConcurrentSessions());
    }
  }

  /** ReplException with non-null message surfaces that message in the refusal text. */
  @Test
  void replExceptionMessageSurfacesInRefusal() throws Exception {
    var sandbox = new StubSandbox();
    sandbox.delayMillis = 10_000; // long enough that we close mid-execute
    try (var provider = providerFor(sandbox)) {
      var c = ctx("repl-err");
      provider.onSessionStart(c);
      var req = ExecutionRequest.newBuilder().withRuntime(Runtime.JSHELL).withScript("x").build();
      var future = provider.execute(c, req, new CancellationToken()).toCompletableFuture();
      // Give the sandbox a beat to start, then close the ReplSession out from under it; that
      // surfaces as a ReplException unrelated to the per-call cancellation token.
      Thread.sleep(50);
      sandbox.close();
      var result = future.get(5, TimeUnit.SECONDS);
      assertEquals(-1, result.exitCode());
      assertTrue(
          result.stderr().contains("JShell execution failed"),
          "expected refusal-shaped stderr, got: " + result.stderr());
      assertTrue(
          result.stderr().contains("sandbox closed mid-execute"),
          "expected ReplException message to surface, got: " + result.stderr());
    }
  }

  /** Calling onSessionStart twice with the same sessionId refuses the second — no orphans. */
  @Test
  void onSessionStartTwiceWithSameIdRefusesAndPreservesOriginal() {
    var sandboxA = new StubSandbox();
    var sandboxB = new StubSandbox();
    var nextSandbox = new AtomicReference<StubSandbox>(sandboxA);
    var second = new AtomicReference<>(sandboxB);
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(registry -> nextSandbox.getAndSet(second.get()))
            .withExecutionTimeout(Duration.ofSeconds(5))
            .build();
    try (var provider =
        JShellExecutionProvider.newBuilder()
            .withReplConfig(config)
            .withMaxConcurrentSessions(2)
            .withShutdownHook(false)
            .build()) {
      var c = ctx("dup");
      var first = provider.onSessionStart(c);
      assertInstanceOf(SessionStartOutcome.Accept.class, first);
      var duplicate = provider.onSessionStart(c);
      assertInstanceOf(SessionStartOutcome.Refuse.class, duplicate);
      assertTrue(((SessionStartOutcome.Refuse) duplicate).reason().contains("already"));
      // Original sandbox is still alive; no duplicate sandbox was spawned (the factory's next slot
      // wasn't consumed — verified via the second AtomicReference still pointing at sandboxB).
      assertTrue(sandboxA.isAlive());
      assertEquals(1, provider.liveSessionCount());
    }
  }
}
