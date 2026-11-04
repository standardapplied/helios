/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.SessionSpansTest.call;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.session.test.SpanCapture;
import com.standardapplied.helios.testing.ScriptedModel;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/**
 * A {@link RuntimeException} the application's OpenTelemetry SDK throws from a span call changes no
 * run: each run's result is the one it has without tracing, and the failure is logged, redacted,
 * naming the session and the run.
 */
final class SessionSpanFailuresTest {

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

  /** Run twice with {@code failing} in the SDK, asserting both results are unchanged. */
  private SpanCapture assertRunsUnchanged(Failing failing, int warningsPerRun) {
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
                  + failing.logged()));
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
    var spans = assertRunsUnchanged(failing("invoke_agent ", true), 1);

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
    assertRunsUnchanged(failing("execute_tool ", false), 1);
  }

  @Test
  void aModelAttemptWhoseSpanDoesNotStartLeavesTheRunsResultUnchanged() {
    var spans = assertRunsUnchanged(failing("chat ", true), 4);

    assertEquals(2, spans.starting("invoke_agent ").size());
  }

  @Test
  void aRunWhoseSpanDoesNotEndLeavesItsResultUnchanged() {
    var spans = assertRunsUnchanged(failing("invoke_agent ", false), 1);

    assertEquals(2, spans.starting("invoke_agent ").size());
  }

  @Test
  void aFailureWhoseMessageCannotBeReadIsLoggedByItsClassName() {
    var unprintable = new EndingSessions.Unprintable();
    assertRunsUnchanged(
        new Failing("execute_tool ", false, unprintable, unprintable.getClass().getName()), 1);
  }

  private static Failing failing(String prefix, boolean atStart) {
    return new Failing(
        prefix,
        atStart,
        new IllegalStateException("span processor failed"),
        "java.lang.IllegalStateException: span processor failed");
  }

  /**
   * Throws {@code failure} when a span whose name starts with {@code prefix} starts, or else when
   * it ends; {@code logged} is the failure as its warning shows it.
   */
  private record Failing(String prefix, boolean atStart, RuntimeException failure, String logged)
      implements SpanProcessor {

    private void fail(String name, boolean starting) {
      if (name.startsWith(prefix) && starting == atStart) {
        throw failure;
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
