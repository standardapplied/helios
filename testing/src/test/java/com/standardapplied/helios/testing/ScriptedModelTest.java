/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScriptedModelTest {

  public record Verdict(String answer, int score) {}

  @Test
  void textTurnReturnsContentWithStopFinish() {
    var model = ScriptedModel.newBuilder().withTextTurn("hello").build();

    var response = model.chat(List.of(Message.user("hi")));

    assertEquals("hello", response.content());
    assertEquals(FinishReason.STOP, response.finishReason());
    assertTrue(response.toolCalls().isEmpty());
    assertEquals(Usage.of(0, 0), response.usage());
  }

  @Test
  void textTurnCarriesScriptedUsage() {
    var model = ScriptedModel.newBuilder().withTextTurn("hello", Usage.of(100, 50, 20, 10)).build();

    var response = model.chat(List.of(Message.user("hi")));

    assertEquals(Usage.of(100, 50, 20, 10), response.usage());
  }

  @Test
  void toolCallTurnReturnsToolCallsWithToolCallsFinish() {
    var call =
        ToolCall.newBuilder()
            .withId("tc-1")
            .withName("search")
            .withArguments(Map.of("query", "helios"))
            .build();
    var model = ScriptedModel.newBuilder().withToolCallsTurn(call).withTextTurn("done").build();

    var first = model.chat(List.of(Message.user("go")));
    assertEquals(FinishReason.TOOL_CALLS, first.finishReason());
    assertEquals(List.of(call), first.toolCalls());

    var second = model.chat(List.of(Message.user("result")));
    assertEquals("done", second.content());
  }

  @Test
  void structuredTurnParsesThroughRealParser() {
    var model =
        ScriptedModel.newBuilder().withTextTurn("{\"answer\": \"yes\", \"score\": 4}").build();

    var response = model.chat(List.of(Message.user("judge")), OutputSchema.of(Verdict.class));

    assertEquals(new Verdict("yes", 4), response.parsed());
  }

  @Test
  void structuredTurnWithSchemaMismatchThrowsParseException() {
    var model = ScriptedModel.newBuilder().withTextTurn("{\"unexpected\": true}").build();

    assertThrows(
        StructuredOutputParseException.class,
        () -> model.chat(List.of(Message.user("judge")), OutputSchema.of(Verdict.class)));
  }

  @Test
  void toolCallTurnSkipsStructuredParse() {
    var call = ToolCall.newBuilder().withId("tc-1").withName("search").build();
    var model = ScriptedModel.newBuilder().withToolCallsTurn(call).build();

    var response =
        model.chat(List.of(Message.user("judge")), List.of(), OutputSchema.of(Verdict.class));

    assertNull(response.parsed());
    assertEquals(List.of(call), response.toolCalls());
  }

  @Test
  void refusalTurnCarriesRefusalFinishReason() {
    var model = ScriptedModel.newBuilder().withRefusalTurn("I can't help with that.").build();

    var response = model.chat(List.of(Message.user("do the thing")));

    assertEquals(FinishReason.REFUSAL, response.finishReason());
    assertEquals("I can't help with that.", response.content());
    assertTrue(response.toolCalls().isEmpty());
  }

  @Test
  void exhaustedScriptFailsFast() {
    var model = ScriptedModel.newBuilder().withTextTurn("only").build();
    model.chat(List.of(Message.user("one")));

    var ex =
        assertThrows(IllegalStateException.class, () -> model.chat(List.of(Message.user("two"))));
    assertTrue(ex.getMessage().contains("1 turn(s) scripted"));
  }

  @Test
  void capturesEveryCallForAssertions() {
    var model = ScriptedModel.newBuilder().withTextTurn("a").withTextTurn("b").build();

    model.chat(List.of(Message.user("first")));
    model.chat(List.of(Message.system("sys"), Message.user("second")));

    assertEquals(2, model.calls().size());
    assertEquals("first", model.calls().get(0).getFirst().content());
    assertEquals(2, model.calls().get(1).size());
  }

  @Test
  void identityDefaultsAndOverride() {
    assertEquals("scripted", ScriptedModel.newBuilder().withTextTurn("x").build().id());
    assertEquals("testing", ScriptedModel.newBuilder().withTextTurn("x").build().provider());
    assertEquals(
        "my-model", ScriptedModel.newBuilder().withId("my-model").withTextTurn("x").build().id());
  }

  @Test
  void worksThroughDefaultStreamingPath() {
    var model = ScriptedModel.newBuilder().withTextTurn("streamed").build();

    try (var stream = model.chatStream(List.of(Message.user("hi")))) {
      var event = stream.next();
      var done = assertInstanceOf(StreamEvent.Done.class, event);
      assertEquals("streamed", done.response().content());
    }
  }
}
