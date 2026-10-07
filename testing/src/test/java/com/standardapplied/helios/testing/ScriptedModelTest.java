/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScriptedModelTest {

  @Test
  void toolCallsTurnWithoutACallIsRejected() {
    var builder = ScriptedModel.newBuilder();

    var thrown = assertThrows(IllegalArgumentException.class, () -> builder.withToolCallsTurn());

    assertEquals("withToolCallsTurn requires at least one tool call", thrown.getMessage());
  }

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

  @Test
  void responseTurnReturnsTheScriptedResponseAsGiven() {
    var scripted =
        Response.newBuilder()
            .withContent("")
            .withFinishReason(FinishReason.REFUSAL)
            .withUsage(Usage.of(412, 0))
            .withThinking("weighing it")
            .withMetadata(Map.of(Response.REFUSAL_CATEGORY_KEY, "cyber"))
            .build();
    var model = ScriptedModel.newBuilder().withResponseTurn(scripted).build();

    assertSame(scripted, model.chat(List.of(Message.user("hi"))));
  }

  @Test
  void structuredResponseTurnParsesAndKeepsItsMetadataAndThinking() {
    var model =
        ScriptedModel.newBuilder()
            .withResponseTurn(
                Response.newBuilder()
                    .withContent("{\"answer\": \"yes\", \"score\": 4}")
                    .withFinishReason(FinishReason.STOP)
                    .withThinking("sure")
                    .withMetadata(Map.of("k", "v"))
                    .build())
            .build();

    var response = model.chat(List.of(Message.user("judge")), OutputSchema.of(Verdict.class));

    assertEquals(new Verdict("yes", 4), response.parsed());
    assertEquals("sure", response.thinking());
    assertEquals(Map.of("k", "v"), response.metadata());
  }

  @Test
  void responseTurnStreamsTheChunksItsResponseSynthesises() {
    var call = new ToolCall("c1", "search", Map.of());
    var model =
        ScriptedModel.newBuilder()
            .withResponseTurn(
                Response.newBuilder()
                    .withContent("calling")
                    .withToolCalls(List.of(call))
                    .withFinishReason(FinishReason.TOOL_CALLS)
                    .withUsage(Usage.of(2, 1))
                    .build())
            .build();

    var recorder =
        ChunkRecorder.drain(
            model.chatStream(List.of(Message.user("go")), List.of(), new CancellationToken()));

    assertEquals(
        List.of(
            "subscribed",
            new ModelChunk.TextDelta("calling").toString(),
            new ModelChunk.ToolUseStart("c1", "search").toString(),
            new ModelChunk.ToolUseStop(call).toString(),
            new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(2, 1), Map.of()).toString(),
            "complete"),
        recorder.signals);
  }

  @Test
  void streamTurnAnswersEitherChatStreamWithItsPublisher() {
    var first = ModelStreams.of(new ModelChunk.TextDelta("a"));
    var second = ModelStreams.failing(new IllegalStateException("cut"));
    var model = ScriptedModel.newBuilder().withStreamTurn(first).withStreamTurn(second).build();
    var schema = OutputSchema.of(Verdict.class);

    assertSame(
        first, model.chatStream(List.of(Message.user("1")), List.of(), new CancellationToken()));
    assertSame(
        second,
        model.chatStream(List.of(Message.user("2")), List.of(), schema, new CancellationToken()));
    assertEquals(List.of(Optional.empty(), Optional.of(schema)), model.outputSchemas());
    assertEquals("2", model.calls().get(1).getFirst().content());
  }

  @Test
  void streamTurnFailsAChatCall() {
    var model = ScriptedModel.newBuilder().withStreamTurn(ModelStreams.of()).build();

    var ex =
        assertThrows(IllegalStateException.class, () -> model.chat(List.of(Message.user("hi"))));
    assertEquals(
        "Scripted turn 1 is a stream turn: it answers chatStream, not chat", ex.getMessage());
  }

  @Test
  void streamingPastTheScriptFailsFast() {
    var model = ScriptedModel.newBuilder().withStreamTurn(ModelStreams.of()).build();
    var token = new CancellationToken();
    model.chatStream(List.of(Message.user("one")), List.of(), token);

    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> model.chatStream(List.of(Message.user("two")), List.of(), token));
    assertEquals("Scripted turns exhausted: 1 turn(s) scripted, call 2 requested", ex.getMessage());
  }

  @Test
  void outputSchemasRecordWhichCallsCarriedASchema() {
    var schema = OutputSchema.of(Verdict.class);
    var model =
        ScriptedModel.newBuilder()
            .withTextTurn("plain")
            .withTextTurn("{\"answer\": \"yes\", \"score\": 4}")
            .withTextTurn("{\"answer\": \"no\", \"score\": 1}")
            .build();

    model.chat(List.of(Message.user("1")));
    model.chat(List.of(Message.user("2")), schema);
    model.chatStream(List.of(Message.user("3")), List.of(), schema, new CancellationToken());

    assertEquals(
        List.of(Optional.empty(), Optional.of(schema), Optional.of(schema)), model.outputSchemas());
  }

  @Test
  void nullArgumentsAreRejected() {
    var builder = ScriptedModel.newBuilder();
    var model = ScriptedModel.newBuilder().withTextTurn("x").build();
    var messages = List.of(Message.user("hi"));
    var schema = OutputSchema.of(Verdict.class);
    var token = new CancellationToken();
    assertThrows(NullPointerException.class, () -> builder.withResponseTurn(null));
    assertThrows(NullPointerException.class, () -> builder.withStreamTurn(null));
    assertThrows(NullPointerException.class, () -> model.chatStream(messages, List.of(), null));
    assertThrows(
        NullPointerException.class,
        () -> model.chatStream(messages, List.of(), (OutputSchema<?>) null, token));
    assertThrows(
        NullPointerException.class, () -> model.chatStream(messages, List.of(), schema, null));
    assertTrue(model.calls().isEmpty(), "a rejected call consumes no turn");
  }
}
