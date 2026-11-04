/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.SessionSpansTest.ERROR_TYPE;
import static com.standardapplied.helios.session.SessionSpansTest.TERMINAL;
import static com.standardapplied.helios.session.SessionSpansTest.assertTotalsEqual;
import static com.standardapplied.helios.session.SessionSpansTest.call;
import static com.standardapplied.helios.session.SessionSpansTest.string;
import static com.standardapplied.helios.session.SessionSpansTest.tool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.test.SpanCapture;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * However a run ends, its span ends exactly once, before the run's result completes, with the run's
 * terminal or error, and every span of the run still open ends with it.
 */
final class SessionSpanEndingsTest {

  private static final String SECRET = "sk-trace-secret-0123456789";

  private final SpanCapture capture = new SpanCapture();
  private final CountDownLatch entered = new CountDownLatch(1);

  private SessionOptions.Builder traced(Model model) {
    return EndingSessions.options(model).withOpenTelemetry(capture.openTelemetry());
  }

  private static ResultMessage run(SessionOptions options) {
    try (var session = AgentSession.open(options)) {
      return Await.value("the result", session.send("go"));
    }
  }

  /** The run's one span, ended, with no span of the run left open. */
  private SpanData endedRun() {
    var run = capture.run();
    assertEquals(Set.of(), capture.unended());
    return run;
  }

  private static void assertFailed(SpanData span, String description, String type) {
    assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    assertEquals(description, span.getStatus().getDescription());
    assertEquals(type, span.getAttributes().get(ERROR_TYPE));
  }

  private CloseableIterator<ModelChunk> stalled() {
    return ModelStreams.stalled(entered::countDown);
  }

  @Test
  void aSuccessfulRunEndsItsSpanUnset() {
    var result = run(traced(ScriptedModel.newBuilder().withTextTurn("done").build()).build());

    var run = endedRun();
    assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("Success", run.getAttributes().get(TERMINAL));
    assertEquals(StatusCode.UNSET, run.getStatus().getStatusCode());
    assertNull(run.getAttributes().get(ERROR_TYPE));
  }

  @Test
  void aRunThatFailedItsExecutionAfterAThrowingToolNamesTheErrorItCarries() {
    var secrets = new SecretRegistry();
    secrets.register("key", SECRET);
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(call("broken", Map.of()))
            .withStreamTurn(ModelStreams.failing(new IllegalStateException("broken " + SECRET)))
            .build();
    var broken =
        EndingSessions.binding(
            "broken",
            (arguments, context) -> {
              throw new IllegalStateException("tool failed");
            });

    var result = run(traced(model).withTool(broken).withSecretRegistry(secrets).build());

    assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result);
    var run = endedRun();
    var description = run.getStatus().getDescription();
    assertTrue(
        description.startsWith("ErrorDuringExecution: java.lang.IllegalStateException: broken"),
        description);
    assertFalse(description.contains(SECRET), description);
    assertEquals("ErrorDuringExecution", run.getAttributes().get(ERROR_TYPE));
    assertEquals("ErrorDuringExecution", run.getAttributes().get(TERMINAL));
    assertFailed(capture.only("execute_tool broken"), "Threw", "Threw");
  }

  @Test
  void aRunWhoseStreamKeptFailingTransientlyNamesTheErrorItCarries() {
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                ModelStreams.failing(new TransientStreamException("reset", null, "test")))
            .build();
    var limits =
        SessionLimits.newBuilder().withStreamRetryPolicy(StreamRetryPolicy.disabled()).build();

    var result = run(traced(model).withLimits(limits).build());

    assertInstanceOf(ResultMessage.ErrorTransientStream.class, result);
    var run = endedRun();
    assertFailed(
        run,
        "ErrorTransientStream: " + TransientStreamException.class.getName() + ": reset",
        "ErrorTransientStream");
    assertEquals("ErrorTransientStream", run.getAttributes().get(TERMINAL));
  }

  @Test
  void aRunInterruptedDuringAModelCallEndsCancelledWithItsAttemptFailed() {
    var model = ScriptedModel.newBuilder().withStreamTurn(stalled()).build();
    ResultMessage result;
    try (var session = AgentSession.open(traced(model).build())) {
      var future = session.send("go");
      Await.latch("the model stream to be read", entered);
      session.interrupt();
      result = Await.value("the result", future);
    }

    assertInstanceOf(ResultMessage.Cancelled.class, result);
    var run = endedRun();
    assertFailed(run, "Cancelled", "Cancelled");
    assertEquals("Cancelled", run.getAttributes().get(TERMINAL));
    var chat = capture.only("chat scripted");
    assertEquals(StatusCode.ERROR, chat.getStatus().getStatusCode());
  }

  @Test
  void aRunInterruptedDuringAToolCallEndsCancelledWithTheCallsSpanEnded() {
    var model = ScriptedModel.newBuilder().withToolCallsTurn(call("waiting", Map.of())).build();
    var waiting =
        EndingSessions.binding(
            "waiting",
            (arguments, context) -> {
              entered.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return ToolResult.success("interrupted");
            });
    ResultMessage result;
    try (var session = AgentSession.open(traced(model).withTool(waiting).build())) {
      var future = session.send("go");
      Await.latch("the tool to run", entered);
      session.interrupt();
      result = Await.value("the result", future);
    }

    assertInstanceOf(ResultMessage.Cancelled.class, result);
    assertFailed(endedRun(), "Cancelled", "Cancelled");
    var tool = capture.only("execute_tool waiting");
    assertEquals(StatusCode.ERROR, tool.getStatus().getStatusCode());
    assertEquals(capture.run().getSpanId(), tool.getParentSpanId());
  }

  @Test
  void aRunPastItsWallClockEndsItsSpanNamingTheTerminal() {
    var model = ScriptedModel.newBuilder().withStreamTurn(stalled()).build();
    var now = new AtomicReference<>(Instant.parse("2026-11-03T10:00:00Z"));
    ResultMessage result;
    try (var session = AgentSession.open(traced(model).withClock(now::get).build())) {
      var future = session.send("go");
      Await.latch("the model stream to be read", entered);
      now.set(now.get().plus(SessionLimits.defaults().maxWallClock()).plusSeconds(1));
      ((AgentSessionImpl) session).lifecycle.expireWallClock();
      result = Await.value("the result", future);
    }

    assertInstanceOf(ResultMessage.ErrorMaxWallClock.class, result);
    var run = endedRun();
    assertFailed(run, "ErrorMaxWallClock", "ErrorMaxWallClock");
    assertEquals("ErrorMaxWallClock", run.getAttributes().get(TERMINAL));
  }

  @Test
  void aRunCancelledByCloseMidTurnEndsItsSpan() {
    var model = ScriptedModel.newBuilder().withStreamTurn(stalled()).build();
    ResultMessage result;
    try (var session = AgentSession.open(traced(model).build())) {
      var future = session.send("go");
      Await.latch("the model stream to be read", entered);
      session.close();
      result = Await.value("the result", future);
    }

    assertInstanceOf(ResultMessage.Cancelled.class, result);
    assertFailed(endedRun(), "Cancelled", "Cancelled");
  }

  @Test
  void aRunThatHitsItsTurnLimitNamesItsTerminal() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(Usage.of(3, 1), call("noop", Map.of()))
            .build();

    var result =
        run(
            traced(model)
                .withTool(tool("noop", "ok"))
                .withLimits(SessionLimits.newBuilder().withMaxTurns(1).build())
                .build());

    assertInstanceOf(ResultMessage.ErrorMaxTurns.class, result);
    var run = endedRun();
    assertFailed(run, "ErrorMaxTurns", "ErrorMaxTurns");
    assertTotalsEqual(result, run);
  }

  @Test
  void aRefusedRunNamesItsTerminal() {
    run(traced(ScriptedModel.newBuilder().withRefusalTurn("no").build()).build());

    assertFailed(endedRun(), "Refusal", "Refusal");
  }

  @Test
  void aHostErrorFromAHookFailsTheRunAndItsSpanNamesTheError() {
    var failure = new HostFailure();
    PreStopHook fatal =
        (response, ctx) -> {
          throw failure;
        };
    try (var session =
        AgentSession.open(
            traced(ScriptedModel.newBuilder().withTextTurn("done").build())
                .withHook(fatal)
                .build())) {
      assertSame(failure, Await.failure("the result to fail", session.send("go")));
    }

    var run = endedRun();
    assertFailed(run, HostFailure.class.getName() + ": host failure", HostFailure.class.getName());
    assertNull(run.getAttributes().get(TERMINAL));
  }

  @Test
  void aToolsHostErrorFailsTheRunAndEndsTheToolsOpenSpanWithTheRunsError() {
    var failure = new HostFailure();
    var model = ScriptedModel.newBuilder().withToolCallsTurn(call("fatal", Map.of())).build();
    var fatal =
        EndingSessions.binding(
            "fatal",
            (arguments, context) -> {
              throw failure;
            });
    try (var session = AgentSession.open(traced(model).withTool(fatal).build())) {
      assertSame(failure, Await.failure("the result to fail", session.send("go")));
    }

    var run = endedRun();
    var error = HostFailure.class.getName() + ": host failure";
    assertFailed(run, error, HostFailure.class.getName());
    var tool = capture.only("execute_tool fatal");
    assertFailed(tool, error, HostFailure.class.getName());
    assertNull(string(tool, "helios.tool.outcome"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void whatEndingAToolsOpenSpanThrowsStillEndsTheRunSpan(boolean error) {
    var failure = new HostFailure();
    Runnable ending =
        error
            ? () -> {
              throw new AssertionError("span processor failed");
            }
            : () -> {
              throw new IllegalStateException("span processor failed");
            };
    var failing =
        new SpanCapture(new SessionSpanFailuresTest.Failing("execute_tool fatal", false, ending));
    var model = ScriptedModel.newBuilder().withToolCallsTurn(call("fatal", Map.of())).build();
    var fatal =
        EndingSessions.binding(
            "fatal",
            (arguments, context) -> {
              throw failure;
            });
    try (var session =
        AgentSession.open(
            EndingSessions.options(model)
                .withOpenTelemetry(failing.openTelemetry())
                .withTool(fatal)
                .build())) {
      assertSame(failure, Await.failure("the result to fail", session.send("go")));
    }

    var run = failing.run();
    assertEquals(Set.of(), failing.unended());
    assertFailed(run, HostFailure.class.getName() + ": host failure", HostFailure.class.getName());
  }

  @ParameterizedTest
  @CsvSource({"true, true", "false, true", "true, false", "false, false"})
  void aModelAttemptAHostErrorEndsFailsTheRunAndEndsTheAttemptWithTheRunsError(
      boolean atOpen, boolean readable) {
    Error failure = readable ? new HostFailure() : new UnreadableHostFailure();
    var model =
        EndingSessions.model(
            () -> {
              if (atOpen) {
                throw failure;
              }
              return ModelStreams.failing(failure);
            });

    try (var session = AgentSession.open(traced(model).build())) {
      assertSame(failure, Await.failure("the result to fail", session.send("go")));
    }

    var run = endedRun();
    var type = failure.getClass().getName();
    var description = readable ? type + ": host failure" : type;
    assertFailed(run, description, type);
    assertFailed(capture.only("chat scripted-stream"), description, type);
    assertNull(run.getAttributes().get(SessionSpansTest.TOTAL_TOKENS));
  }

  @Test
  void aRuntimeExceptionTheLoopCannotTurnIntoATerminalFailsTheRunSpanAsItself() {
    var failure = new EndingSessions.Unprintable();
    var model =
        EndingSessions.model(
            () -> {
              throw failure;
            });

    try (var session = AgentSession.open(traced(model).build())) {
      assertSame(failure, Await.failure("the result to fail", session.send("go")));
    }

    var name = EndingSessions.Unprintable.class.getName();
    assertFailed(endedRun(), name, name);
  }

  /** A host failure a hook, a tool or a model throws. */
  static final class HostFailure extends Error {

    HostFailure() {
      super("host failure");
    }
  }

  /** A host {@link Error} whose message cannot be read: {@link #getMessage()} throws. */
  private static final class UnreadableHostFailure extends Error {
    @Override
    public String getMessage() {
      throw new IllegalStateException("unreadable");
    }
  }
}
