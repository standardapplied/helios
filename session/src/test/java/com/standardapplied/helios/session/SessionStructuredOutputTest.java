/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.BlockingModel;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A session's structured output: typed {@code runBlocking} parses the final answer against an
 * {@link OutputSchema}, and a configured schema rides every model turn to the provider.
 */
final class SessionStructuredOutputTest {

  private static final String SID = "sess-structured";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-05-14T19:00:00Z"), ZoneOffset.UTC);

  private static AgentSession buildSession(Model model) {
    return AgentSession.create(
        SessionOptions.newBuilder().withModel(model).withSessionId(SID).withClock(CLOCK).build());
  }

  private static Model textOnceModel(String reply, FinishReason finishReason) {
    return ScriptedModel.newBuilder()
        .withResponseTurn(
            Response.newBuilder()
                .withContent(reply)
                .withFinishReason(finishReason)
                .withUsage(Usage.of(3, 2))
                .build())
        .build();
  }

  /** Output record used by the typed-runBlocking tests below. */
  public record TypedAnswer(String name, int score) {}

  @Test
  void typedRunBlockingParsesFinalAssistantTextAgainstSchema() {
    var json = "{\"name\":\"alice\",\"score\":42}";
    try (var s = buildSession(textOnceModel(json, FinishReason.STOP))) {
      var result = s.runBlocking(UserMessage.text("hi"), OutputSchema.of(TypedAnswer.class));
      assertEquals("alice", result.name());
      assertEquals(42, result.score());
    }
  }

  @Test
  void typedRunBlockingToleratesMarkdownFences() {
    var fenced = "```json\n{\"name\":\"bob\",\"score\":7}\n```";
    try (var s = buildSession(textOnceModel(fenced, FinishReason.STOP))) {
      var result = s.runBlocking(UserMessage.text("hi"), OutputSchema.of(TypedAnswer.class));
      assertEquals("bob", result.name());
      assertEquals(7, result.score());
    }
  }

  @Test
  void typedRunBlockingInheritsDisabledRawOutputCaptureFromModel() {
    var canary = "session-raw-output-canary";
    var delegate = textOnceModel("{\"name\":\"" + canary + "\"}", FinishReason.STOP);
    Model privacyModel =
        new BlockingModel() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return delegate.chat(messages, tools);
          }

          @Override
          public String id() {
            return delegate.id();
          }

          @Override
          public String provider() {
            return delegate.provider();
          }

          @Override
          public RawOutputCapturePolicy rawOutputCapturePolicy() {
            return RawOutputCapturePolicy.DISABLED;
          }
        };

    try (var session = buildSession(privacyModel)) {
      var error =
          assertThrows(
              StructuredOutputParseException.class,
              () ->
                  session.runBlocking(UserMessage.text("hi"), OutputSchema.of(TypedAnswer.class)));

      assertNull(error.rawContent());
      assertFalse(error.getMessage().contains(canary));
    }
  }

  @Test
  void typedRunBlockingRejectsNullMessage() {
    try (var s = buildSession(textOnceModel("{}", FinishReason.STOP))) {
      assertThrows(
          NullPointerException.class,
          () -> s.runBlocking(null, OutputSchema.of(TypedAnswer.class)));
    }
  }

  @Test
  void typedRunBlockingRejectsNullSchema() {
    try (var s = buildSession(textOnceModel("{}", FinishReason.STOP))) {
      assertThrows(
          NullPointerException.class,
          () -> s.runBlocking(UserMessage.text("hi"), (OutputSchema<?>) null));
    }
  }

  @Test
  void typedRunBlockingThrowsOnNonSuccessTerminal() {
    // CONTENT_FILTER produces a Refusal terminal — typed runBlocking has nothing to parse.
    try (var s = buildSession(textOnceModel("model refusal", FinishReason.CONTENT_FILTER))) {
      var ex =
          assertThrows(
              IllegalStateException.class,
              () -> s.runBlocking(UserMessage.text("hi"), OutputSchema.of(TypedAnswer.class)));
      assertTrue(ex.getMessage().contains("Refusal"));
    }
  }

  /** Output record whose simple name the blank-result message names. */
  public record Person(String name) {}

  @ParameterizedTest
  @ValueSource(strings = {"", " \n\t "})
  void typedRunBlockingThrowsWhenSuccessCarriesABlankResult(String blank) {
    try (var s = buildSession(textOnceModel(blank, FinishReason.STOP))) {
      var ex =
          assertThrows(
              IllegalStateException.class,
              () -> s.runBlocking(UserMessage.text("hi"), OutputSchema.of(Person.class)));
      assertEquals(
          "session ended with Success but produced no result to parse as Person", ex.getMessage());
    }
  }

  @Test
  void untypedRunBlockingReturnsSuccessWithTheEmptyResult() {
    try (var s = buildSession(textOnceModel("", FinishReason.STOP))) {
      var terminal = s.runBlocking(UserMessage.text("hi"));
      assertEquals("", assertInstanceOf(ResultMessage.Success.class, terminal).result());
    }
  }

  /** Sample record so we can build a real {@link OutputSchema} for the wiring tests. */
  public record Sample(String field) {}

  /** A model whose one turn streams {@code json} as a finished text answer. */
  private static ScriptedModel streamingJson(String json) {
    return ScriptedModel.newBuilder()
        .withStreamTurn(
            ModelStreams.of(
                new ModelChunk.TextDelta(json),
                new ModelChunk.MessageStop(FinishReason.STOP.name(), Usage.of(1, 1), Map.of())))
        .build();
  }

  @Test
  void configuredOutputSchemaRidesEveryTurnAndCarriesThroughToTheProvider() throws Exception {
    var schema = OutputSchema.of(Sample.class);
    var model = streamingJson("{\"field\":\"typed-with-schema\"}");
    try (var session =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(model)
                .withSessionId(SID)
                .withClock(CLOCK)
                .withOutputSchema(schema)
                .build())) {
      var typed = session.runBlocking(UserMessage.text("go"), schema);
      assertEquals("typed-with-schema", typed.field());
    }
    var schemas = model.outputSchemas();
    assertTrue(
        schemas.stream().anyMatch(Optional::isPresent),
        "configured outputSchema must route through the schema-bearing chatStream so the provider's"
            + " native structured-output channel sees the schema on every turn — the matchmaking"
            + " bug regression");
    assertEquals(Optional.of(schema), schemas.getFirst());
    assertFalse(schemas.contains(Optional.empty()));
  }

  @Test
  void sessionWithoutOutputSchemaUsesTheUnconstrainedDispatch() throws Exception {
    var model = streamingJson("{\"field\":\"untyped\"}");
    try (var session =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(model)
                .withSessionId(SID)
                .withClock(CLOCK)
                .build())) {
      var terminal = session.runBlocking(UserMessage.text("go"));
      var success = assertInstanceOf(ResultMessage.Success.class, terminal);
      assertEquals("{\"field\":\"untyped\"}", success.result());
    }
    assertTrue(
        model.outputSchemas().contains(Optional.empty()),
        "no outputSchema configured: the loop dispatches the unconstrained chatStream so the model"
            + " is free to produce arbitrary text");
    assertFalse(model.outputSchemas().stream().anyMatch(Optional::isPresent));
  }
}
