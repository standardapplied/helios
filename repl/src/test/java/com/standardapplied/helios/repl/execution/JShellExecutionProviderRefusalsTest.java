/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.repl.ReplConfig;
import com.standardapplied.helios.repl.ReplException;
import com.standardapplied.helios.repl.sandbox.ExecutionRequest;
import com.standardapplied.helios.repl.sandbox.ExecutionResult;
import com.standardapplied.helios.repl.sandbox.Sandbox;
import com.standardapplied.helios.repl.sandbox.SandboxFactory;
import com.standardapplied.helios.session.execution.Runtime;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * The exact text of every refusal and failure the provider reports, the warning it logs when a
 * sandbox fails to close, and the start that loses the race to bind a session id.
 */
class JShellExecutionProviderRefusalsTest {

  @Test
  void startPastTheCapIsRefusedNamingTheCap() {
    try (var provider = provider(config(code -> ok()), 1)) {
      provider.onSessionStart(ctx("first"));

      assertEquals(
          "JShell session pool saturated (cap=1)", refusal(provider.onSessionStart(ctx("second"))));
    }
  }

  @Test
  void secondStartForABoundIdIsRefused() {
    try (var provider = provider(config(code -> ok()), 2)) {
      provider.onSessionStart(ctx("s"));

      assertEquals(
          "session s already has a JShell sandbox bound",
          refusal(provider.onSessionStart(ctx("s"))));
      assertEquals(1, provider.liveSessionCount());
    }
  }

  @Test
  void startThatLosesTheRaceToBindItsIdClosesItsSandboxAndGivesThePermitBack() {
    var spawned = new ArrayList<RecordingSandbox>();
    var inner = new CompletableFuture<SessionStartOutcome>();
    var holder = new ArrayList<JShellExecutionProvider>();
    SandboxFactory factory =
        registry -> {
          var sandbox = new RecordingSandbox(code -> ok());
          spawned.add(sandbox);
          if (spawned.size() == 1) {
            inner.complete(holder.getFirst().onSessionStart(ctx("s")));
          }
          return sandbox;
        };
    try (var provider = provider(ReplConfig.newBuilder().withSandboxFactory(factory).build(), 2)) {
      holder.add(provider);

      var outer = provider.onSessionStart(ctx("s"));

      assertSame(SessionStartOutcome.accept(), inner.join());
      assertEquals("session s already has a JShell sandbox bound", refusal(outer));
      assertTrue(spawned.get(0).closed);
      assertFalse(spawned.get(1).closed);
      assertEquals(1, provider.liveSessionCount());
      assertSame(SessionStartOutcome.accept(), provider.onSessionStart(ctx("t")));
      assertEquals(
          "JShell session pool saturated (cap=2)", refusal(provider.onSessionStart(ctx("u"))));
    }
  }

  @Test
  void startWhoseSandboxCannotSpawnIsRefusedWithTheFailure() {
    SandboxFactory failing =
        registry -> {
          throw new IllegalStateException("no java binary");
        };
    try (var provider = provider(ReplConfig.newBuilder().withSandboxFactory(failing).build(), 1)) {
      assertEquals(
          "failed to spawn JShell sandbox for session s: Failed to create session",
          refusal(provider.onSessionStart(ctx("s"))));
    }
  }

  @Test
  void closedProviderRefusesStartsAndFailsExecutes() {
    var provider = provider(config(code -> ok()), 1);
    provider.close();

    assertEquals("provider is closed", refusal(provider.onSessionStart(ctx("s"))));
    var failure =
        Await.failure(
            "the execution on a closed provider",
            provider
                .execute(ctx("s"), request(Runtime.JSHELL), new CancellationToken())
                .toCompletableFuture());
    assertInstanceOf(IllegalStateException.class, failure);
    assertEquals("provider is closed", failure.getMessage());
  }

  @Test
  void executeForAnUnknownSessionOrAnotherRuntimeIsRefused() {
    try (var provider = provider(config(code -> ok()), 1)) {
      assertEquals(
          "JShellExecutionProvider: no JShell session registered for sessionId=ghost — "
              + "onSessionStart not called or already onSessionEnd'd (runtime=JSHELL)",
          execute(provider, "ghost", request(Runtime.JSHELL)).stderr());
      assertEquals(
          "JShellExecutionProvider: runtime not supported (runtime=BASH)",
          execute(provider, "ghost", request(Runtime.BASH)).stderr());
    }
  }

  @Test
  void sandboxFailureIsRefusedWithItsMessageOrItsTypeWhenThereIsNone() {
    var failures =
        new ArrayList<>(List.of(new ReplException("boom"), new ReplException((String) null)));
    try (var provider =
        provider(
            config(
                code -> {
                  throw failures.removeFirst();
                }),
            1)) {
      provider.onSessionStart(ctx("s"));

      assertEquals(
          "JShellExecutionProvider: JShell execution failed: boom (runtime=JSHELL)",
          execute(provider, "s", request(Runtime.JSHELL)).stderr());
      assertEquals(
          "JShellExecutionProvider: JShell execution failed: ReplException (runtime=JSHELL)",
          execute(provider, "s", request(Runtime.JSHELL)).stderr());
    }
  }

  @Test
  void executeRunsOnAThreadNamedForTheSession() {
    var threadName = new CompletableFuture<String>();
    try (var provider =
        provider(
            config(
                code -> {
                  threadName.complete(Thread.currentThread().getName());
                  return ok();
                }),
            1)) {
      provider.onSessionStart(ctx("s"));

      execute(provider, "s", request(Runtime.JSHELL));

      assertEquals("helios-jshell-s", threadName.join());
    }
  }

  @Test
  void cancelledExecuteFailsWithTheCancellationReason() {
    try (var provider = provider(config(code -> ok()), 1)) {
      provider.onSessionStart(ctx("s"));
      var token = new CancellationToken();
      token.cancel("user stop");

      var failure =
          Await.failure(
              "the cancelled execution",
              provider.execute(ctx("s"), request(Runtime.JSHELL), token).toCompletableFuture());

      assertInstanceOf(CancellationException.class, failure);
      assertEquals("JShell snippet cancelled: user stop", failure.getMessage());
    }
  }

  @Test
  void sandboxThatFailsToCloseIsLoggedAndItsPermitGivenBack() {
    var failure = new IllegalStateException("close failed");
    var sandbox =
        new RecordingSandbox(code -> ok()) {
          @Override
          public void close() {
            throw failure;
          }
        };
    var logger = Logger.getLogger(JShellExecutionProvider.class.getName());
    var records = new ArrayList<LogRecord>();
    var handler =
        new Handler() {
          @Override
          public void publish(LogRecord logRecord) {
            records.add(logRecord);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    try (var provider =
        provider(ReplConfig.newBuilder().withSandboxFactory(r -> sandbox).build(), 1)) {
      provider.onSessionStart(ctx("s"));

      provider.onSessionEnd(ctx("s"));

      assertEquals(1, records.size());
      assertEquals(Level.WARNING, records.getFirst().getLevel());
      assertEquals("failed to close ReplSession", records.getFirst().getMessage());
      assertSame(failure, records.getFirst().getThrown());
      assertEquals(0, provider.liveSessionCount());
      assertSame(SessionStartOutcome.accept(), provider.onSessionStart(ctx("t")));
    } finally {
      logger.removeHandler(handler);
    }
  }

  private static JShellExecutionProvider provider(ReplConfig config, int maxSessions) {
    return JShellExecutionProvider.newBuilder()
        .withReplConfig(config)
        .withMaxConcurrentSessions(maxSessions)
        .withShutdownHook(false)
        .build();
  }

  private static ReplConfig config(Function<String, ExecutionResult> script) {
    return ReplConfig.newBuilder().withSandboxFactory(r -> new RecordingSandbox(script)).build();
  }

  private static String refusal(SessionStartOutcome outcome) {
    return assertInstanceOf(SessionStartOutcome.Refuse.class, outcome).reason();
  }

  private static com.standardapplied.helios.session.execution.ExecutionResult execute(
      JShellExecutionProvider provider,
      String sessionId,
      com.standardapplied.helios.session.execution.ExecutionRequest request) {
    return Await.value(
        "the execution",
        provider.execute(ctx(sessionId), request, new CancellationToken()).toCompletableFuture());
  }

  private static com.standardapplied.helios.session.execution.ExecutionRequest request(
      Runtime runtime) {
    return com.standardapplied.helios.session.execution.ExecutionRequest.newBuilder()
        .withRuntime(runtime)
        .withScript("1")
        .build();
  }

  private static SessionContext ctx(String id) {
    return SessionContext.forTesting(id);
  }

  private static ExecutionResult ok() {
    return ExecutionResult.newBuilder().withStdout("ok").build();
  }

  /** A sandbox that answers each snippet through its script and records whether it was closed. */
  private static class RecordingSandbox implements Sandbox {
    private final Function<String, ExecutionResult> script;
    volatile boolean closed;

    RecordingSandbox(Function<String, ExecutionResult> script) {
      this.script = script;
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
      return script.apply(request.code());
    }

    @Override
    public boolean isAlive() {
      return !closed;
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
