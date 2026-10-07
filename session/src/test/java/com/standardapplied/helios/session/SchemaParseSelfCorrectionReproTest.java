/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the session-loop schema self-correction fix landed in 2.3.3.
 *
 * <p>Pre-2.3.3 the session terminated as {@link ResultMessage.ErrorDuringExecution} on the first
 * {@link StructuredOutputParseException} (CLAUDE.md's "Structured Output Resilience" row was
 * aspirational). Now the loop appends the model's wrong attempt to history, injects a corrective
 * synthetic user message naming each schema-validator error, and iterates until the model converges
 * (bounded by {@link SessionLimits#maxTurns()}).
 */
class SchemaParseSelfCorrectionReproTest {

  public record Sample(String field) {}

  private static final String SID = "sess-schema-self-correct";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-05-23T00:00:00Z"), ZoneOffset.UTC);
  private static final String RECOVERED = "{\"field\":\"recovered\"}";

  private static StructuredOutputParseException missingField(String rawContent) {
    return new StructuredOutputParseException(List.of("field is required but missing"), rawContent);
  }

  private static ResultMessage run(ScriptedModel model, SessionLimits limits) {
    try (var session =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(model)
                .withSessionId(SID)
                .withClock(CLOCK)
                .withOutputSchema(OutputSchema.of(Sample.class))
                .withLimits(limits)
                .build())) {
      var terminal = session.runBlocking(UserMessage.text("go"));
      assertTrue(
          model.outputSchemas().stream().allMatch(Optional::isPresent),
          "every turn, the corrective retries included, must carry the session's output schema");
      return terminal;
    }
  }

  /**
   * The model's first typed turn fails with {@link StructuredOutputParseException} (mirroring a
   * real provider that received parseable JSON which didn't match the schema). The second turn
   * returns a valid Sample so a self-correcting loop converges on the second iteration.
   */
  @Test
  void sessionSelfCorrectsOnStructuredOutputParseExceptionAndConvergesOnSecondTurn() {
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(ModelStreams.failing(missingField("{\"wrong\":\"shape\"}")))
            .withTextTurn(RECOVERED, Usage.of(1, 1))
            .build();
    var terminal = run(model, SessionLimits.defaults());
    var success = assertInstanceOf(ResultMessage.Success.class, terminal);
    assertTrue(
        success.result().contains("recovered"),
        "second-turn clean JSON must surface as the terminal result: " + success.result());
    assertEquals(2, model.calls().size(), "model was called twice: first errored, second clean");
  }

  /**
   * The model <i>never</i> produces a clean response. Validates that self-correction is bounded by
   * the existing {@code maxTurns} ceiling — the session eventually terminates as {@link
   * ResultMessage.ErrorMaxTurns} instead of looping forever.
   */
  @Test
  void persistentParseFailureTerminatesAtMaxTurnsCeiling() {
    var model = ScriptedModel.newBuilder();
    for (var attempt = 1; attempt <= 3; attempt++) {
      model.withStreamTurn(
          ModelStreams.failing(missingField("{\"wrong\":\"shape-" + attempt + "\"}")));
    }
    var scripted = model.build();
    var terminal = run(scripted, SessionLimits.newBuilder().withMaxTurns(3).build());
    assertInstanceOf(ResultMessage.ErrorMaxTurns.class, terminal);
    assertEquals(3, scripted.calls().size(), "model retried up to maxTurns then terminated");
  }

  /**
   * Streaming-provider variant — the model emits some text deltas before the parse error fires and
   * the exception itself carries no {@code rawContent}. TurnRunner must fall back to the
   * subscriber's accumulated content so the assistant message in history still reflects what the
   * model attempted.
   */
  @Test
  void streamingProviderWithNullRawContentFallsBackToSubscriberAccumulatedText() {
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                ModelStreams.failing(
                    missingField(null), new ModelChunk.TextDelta("{\"wrong\":\"shape\"}")))
            .withTextTurn(RECOVERED, Usage.of(1, 1))
            .build();
    var terminal = run(model, SessionLimits.defaults());
    var success = assertInstanceOf(ResultMessage.Success.class, terminal);
    assertTrue(success.result().contains("recovered"));
    assertEquals(2, model.calls().size());
  }
}
