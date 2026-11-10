/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.SessionSpansTest.call;
import static com.standardapplied.helios.session.SessionSpansTest.tool;
import static io.opentelemetry.api.common.AttributeKey.booleanKey;
import static io.opentelemetry.api.common.AttributeKey.longKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.OnUserMessageHook;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.test.SpanCapture;
import com.standardapplied.helios.testing.ScriptedModel;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Each step span of a run carries {@code helios.turn.index}, the {@code turnIndex} of the events it
 * is built from, so a trace reader joins a run's events to its spans.
 */
final class SessionSpanTurnsTest {

  static final AttributeKey<Long> TURN_INDEX = longKey("helios.turn.index");
  static final AttributeKey<Boolean> COMPACTION = booleanKey("helios.compaction");

  private final SpanCapture capture = new SpanCapture();

  private SessionOptions.Builder traced(Model model) {
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId("sess-turns")
        .withOpenTelemetry(capture.openTelemetry());
  }

  /** The events of one run of {@code options}, read to its {@code RunEnded}. */
  private static EventCollector events(SessionOptions options) {
    var events = new EventCollector();
    try (var session = AgentSession.open(options)) {
      events.read(session.events());
      Await.value("the result", session.send("go"));
      events.awaitRunEnded();
    }
    return events;
  }

  private static List<Long> turnIndexes(List<SpanData> spans) {
    return spans.stream().map(span -> span.getAttributes().get(TURN_INDEX)).toList();
  }

  @Test
  void eachStepSpanCarriesTheTurnIndexOfTheEventsItIsBuiltFrom() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(call("first", Map.of()), call("second", Map.of()))
            .withToolCallsTurn(call("third", Map.of()))
            .withTextTurn("done")
            .build();
    var options =
        traced(model)
            .withTool(tool("first", "ok"))
            .withTool(tool("second", "ok"))
            .withTool(tool("third", "ok"))
            .build();

    var events = events(options);

    var chats = capture.named("chat scripted");
    assertEquals(List.of(1L, 2L, 3L), turnIndexes(chats));
    assertEquals(
        events.eventsOf(QueryEvent.ModelCallEnded.class).stream()
            .map(QueryEvent::turnIndex)
            .toList(),
        turnIndexes(chats));
    var uses = events.eventsOf(QueryEvent.ToolUse.class);
    assertEquals(List.of(1L, 1L, 2L), uses.stream().map(QueryEvent::turnIndex).toList());
    for (var use : uses) {
      var span = capture.only("execute_tool " + use.call().name());
      assertEquals(use.turnIndex(), span.getAttributes().get(TURN_INDEX));
    }
    for (var span : capture.spans()) {
      assertNull(span.getAttributes().get(COMPACTION), span.getName());
    }
  }

  @Test
  void eachHookSpanCarriesTheTurnIndexOfItsHookFiredFromTheFirstMessageToTheStop() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(call("first", Map.of()))
            .withToolCallsTurn(call("second", Map.of()))
            .withTextTurn("done")
            .build();
    OnUserMessageHook prompt = (message, context) -> HookOutcome.mutateText("rewritten");
    PreToolUseHook second =
        (call, context) ->
            call.name().equals("second")
                ? HookOutcome.mutateArgs(Map.of("q", "x"))
                : HookOutcome.cont();
    PreStopHook stop = (response, context) -> HookOutcome.stop("final");
    var options =
        traced(model)
            .withTool(tool("first", "ok"))
            .withTool(tool("second", "ok"))
            .withHook(prompt)
            .withHook(second)
            .withHook(stop)
            .build();

    var events = events(options);

    var fired = events.eventsOf(QueryEvent.HookFired.class);
    assertEquals(3, fired.size());
    for (var hook : fired) {
      var span = capture.only("hook " + hook.hookName());
      assertEquals(hook.turnIndex(), span.getAttributes().get(TURN_INDEX), hook.hookName());
    }
    assertEquals(0L, capture.only("hook " + prompt.name()).getAttributes().get(TURN_INDEX));
    assertEquals(2L, capture.only("hook " + second.name()).getAttributes().get(TURN_INDEX));
    assertEquals(3L, capture.only("hook " + stop.name()).getAttributes().get(TURN_INDEX));
    assertEquals(2L, capture.only("execute_tool second").getAttributes().get(TURN_INDEX));
  }
}
