/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.SessionSpansTest.call;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.session.test.SpanCapture;
import com.standardapplied.helios.testing.ScriptedModel;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/**
 * A {@link RuntimeException} the application's OpenTelemetry SDK throws from a span call changes no
 * run: each run's result is the one it has without tracing, and the failure is logged, redacted,
 * naming the session and the run. An {@link Error} a span call throws fails the run with it.
 */
final class SessionSpanFailuresTest {

  private static final String LOGGED = "java.lang.IllegalStateException: span processor failed";

  private final List<LogRecord> logged = new CopyOnWriteArrayList<>();

  /**
   * The results of two runs, each sent under a request span of its own, of a session whose tool
   * records a span of its own, traced through {@code spans} when {@code traced}.
   */
  private List<ResultMessage> twoRuns(SpanCapture spans, boolean traced) {
    var scripted =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(call("lookup", Map.of()))
            .withTextTurn("one")
            .withToolCallsTurn(call("lookup", Map.of()))
            .withTextTurn("two")
            .build();
    var model =
        EndingSessions.model(
            () -> {
              spans.tracer().spanBuilder("model request").startSpan().end();
              return scripted.chatStream(List.of(), List.of());
            });
    var lookup =
        EndingSessions.binding(
            "lookup",
            (arguments, context) -> {
              spans.tracer().spanBuilder("database query").startSpan().end();
              return ToolResult.success("found");
            });
    var options = EndingSessions.options(model).withTool(lookup);
    if (traced) {
      options.withOpenTelemetry(spans.openTelemetry());
    }
    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session = AgentSession.open(options.build())) {
      return List.of(send(spans, session, "one"), send(spans, session, "two"));
    }
  }

  private static ResultMessage send(SpanCapture spans, AgentSession session, String message) {
    var request = spans.tracer().spanBuilder("request " + message).startSpan();
    try (var scope = request.makeCurrent()) {
      return Await.value("the run", session.send(message));
    } finally {
      request.end();
    }
  }

  /**
   * Run twice with {@code failing} in the SDK, asserting both results are unchanged and each run
   * logged {@code warningsPerRun} warnings of the failure, shown as {@code shown}.
   */
  private SpanCapture assertRunsUnchanged(Failing failing, String shown, int warningsPerRun) {
    var plain = twoRuns(new SpanCapture(), false);
    var spans = new SpanCapture(failing);
    var traced = twoRuns(spans, true);

    for (var i = 0; i < plain.size(); i++) {
      var expected = assertInstanceOf(ResultMessage.Success.class, plain.get(i));
      var actual = assertInstanceOf(ResultMessage.Success.class, traced.get(i));
      assertEquals(expected.result(), actual.result());
      assertEquals(expected.usage(), actual.usage());
      assertEquals(expected.cost(), actual.cost());
    }
    var expected = new ArrayList<String>();
    for (var run : traced) {
      expected.addAll(
          Collections.nCopies(
              warningsPerRun,
              "a span of run "
                  + run.runId()
                  + " of session "
                  + EndingSessions.SID
                  + " failed; the run's result is unchanged: "
                  + shown));
    }
    var warnings =
        logged.stream()
            .filter(record -> record.getLevel() == Level.WARNING)
            .map(record -> record.getMessage() + ": " + record.getThrown().getMessage())
            .toList();
    assertEquals(expected, warnings);
    return spans;
  }

  @Test
  void aRunWhoseSpanDoesNotStartRunsUntracedUnderItsCallerAndTheNextRunToo() {
    var spans = assertRunsUnchanged(failing("invoke_agent ", true), LOGGED, 1);

    var requests = spans.starting("request ");
    var queries = spans.named("database query");
    var calls = spans.named("model request");
    assertEquals(requests.get(0).getSpanId(), queries.get(0).getParentSpanId());
    assertEquals(requests.get(1).getSpanId(), queries.get(1).getParentSpanId());
    assertEquals(requests.get(0).getSpanId(), calls.get(0).getParentSpanId());
    assertEquals(requests.get(1).getSpanId(), calls.get(3).getParentSpanId());
    assertEquals(List.of(), spans.starting("chat "));
    assertEquals(List.of(), spans.starting("execute_tool "));
  }

  @Test
  void anEventWhoseSpanDoesNotEndLeavesTheRunsResultUnchanged() {
    assertRunsUnchanged(failing("execute_tool ", false), LOGGED, 1);
  }

  @Test
  void aModelAttemptWhoseSpanDoesNotStartLeavesTheRunsResultUnchanged() {
    var spans = assertRunsUnchanged(failing("chat ", true), LOGGED, 4);

    assertEquals(2, spans.starting("invoke_agent ").size());
  }

  @Test
  void aRunWhoseSpanDoesNotEndLeavesItsResultUnchanged() {
    var spans = assertRunsUnchanged(failing("invoke_agent ", false), LOGGED, 1);

    assertEquals(2, spans.starting("invoke_agent ").size());
  }

  @Test
  void aFailureWhoseMessageCannotBeReadIsLoggedByItsClassName() {
    var unprintable = new EndingSessions.Unprintable();
    assertRunsUnchanged(
        new Failing(
            "execute_tool ",
            false,
            () -> {
              throw unprintable;
            }),
        unprintable.getClass().getName(),
        1);
  }

  @Test
  void anExporterThatThrowsLeavesTheRunsResultAsWithoutTracing() {
    var plain =
        assertInstanceOf(
            ResultMessage.Success.class,
            runOnce(
                EndingSessions.options(SessionSpanContextTest.twoTurns()), new CountDownLatch(0)));
    var exported = new CountDownLatch(1);
    var sdk = new SpanCapture(SimpleSpanProcessor.create(new Throwing(exported))).openTelemetry();

    var traced =
        assertInstanceOf(
            ResultMessage.Success.class,
            runOnce(
                EndingSessions.options(SessionSpanContextTest.twoTurns()).withOpenTelemetry(sdk),
                exported));

    assertEquals(plain.result(), traced.result());
    assertEquals(plain.usage(), traced.usage());
    assertEquals(plain.cost(), traced.cost());
  }

  private static ResultMessage runOnce(SessionOptions.Builder options, CountDownLatch exported) {
    try (var session =
        AgentSession.open(options.withTool(SessionSpansTest.tool("noop", "ok")).build())) {
      var result = Await.value("the result", session.send("go"));
      Await.latch("the exporter to have thrown", exported);
      return result;
    }
  }

  @Test
  void anErrorASpanProcessorThrowsEndingTheRunsSpanFailsTheRunWithThatError() {
    var failure = new SessionSpanEndingsTest.HostFailure();
    var sdk =
        new SpanCapture(
                new Failing(
                    "invoke_agent ",
                    false,
                    () -> {
                      throw failure;
                    }))
            .openTelemetry();
    var options =
        EndingSessions.options(ScriptedModel.newBuilder().withTextTurn("done").build())
            .withOpenTelemetry(sdk);

    try (var session = AgentSession.open(options.build())) {
      assertSame(failure, Await.failure("the result to fail", session.send("go")));
    }
  }

  @Test
  void anErrorASpanProcessorThrowsAfterACloseReturnedStillFailsTheRunWithThatError() {
    var failure = new SessionSpanEndingsTest.HostFailure();
    var entered = new CountDownLatch(1);
    var released = new CountDownLatch(1);
    Runnable failing =
        () -> {
          entered.countDown();
          Uninterruptible.await(released);
          throw failure;
        };
    var sdk = new SpanCapture(new Failing("invoke_agent ", false, failing)).openTelemetry();
    var options =
        EndingSessions.options(ScriptedModel.newBuilder().withTextTurn("done").build())
            .withLimits(EndingSessions.grace())
            .withOpenTelemetry(sdk);
    var session = AgentSession.open(options.build());
    var result = session.send("go");
    Await.latch("the run's span to end", entered);

    session.close();
    assertFalse(result.isDone(), "the close returned before the run's span ended");
    released.countDown();

    assertSame(failure, Await.failure("the result to fail", result));
  }

  @Test
  void anErrorStartingTheRunsSpanFailsTheRunWithThatError() {
    var failure = new SessionSpanEndingsTest.HostFailure();
    var sdk =
        new SpanCapture(
                new Failing(
                    "invoke_agent ",
                    true,
                    () -> {
                      throw failure;
                    }))
            .openTelemetry();
    PreStopHook never =
        (response, ctx) -> {
          throw new AssertionError("the loop ran");
        };
    var options =
        EndingSessions.options(ScriptedModel.newBuilder().withTextTurn("done").build())
            .withHook(never)
            .withOpenTelemetry(sdk);

    try (var session = AgentSession.open(options.build())) {
      assertSame(failure, Await.failure("the result to fail", session.send("go")));
    }
  }

  private static Failing failing(String prefix, boolean atStart) {
    return new Failing(
        prefix,
        atStart,
        () -> {
          throw new IllegalStateException("span processor failed");
        });
  }

  /** An exporter that throws from every export, counting down {@code exported} first. */
  private record Throwing(CountDownLatch exported) implements SpanExporter {

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
      exported.countDown();
      throw new IllegalStateException("export failed");
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }

  /**
   * A span processor that runs {@code failing} when a span whose name starts with {@code prefix}
   * starts, or else when it ends.
   */
  record Failing(String prefix, boolean atStart, Runnable failing) implements SpanProcessor {

    private void fail(String name, boolean starting) {
      if (name.startsWith(prefix) && starting == atStart) {
        failing.run();
      }
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
      fail(span.getName(), true);
    }

    @Override
    public boolean isStartRequired() {
      return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
      fail(span.getName(), false);
    }

    @Override
    public boolean isEndRequired() {
      return true;
    }
  }
}
