/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.ValidationResult;
import ai.singlr.core.model.Role;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.schema.SubmitValidationException;
import ai.singlr.testing.ScriptedModel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The {@link OutputSchema#submitValidator()} contract on the plain {@link AgentSession} path: a
 * structurally valid but semantically invalid final answer must trigger the same self-correction
 * loop a schema mismatch does, bounded by {@link SessionLimits#maxTurns()}, and the typed {@code
 * runBlocking} must never hand back a value the validator rejects.
 */
class SubmitValidatorSelfCorrectionTest {

  public record Answer(String kind, String body) {}

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC);
  private static final String CORRECTION = "kind must be 'people'; fix and resubmit";
  private static final OutputSchema<Answer> PEOPLE_ONLY =
      OutputSchema.of(Answer.class)
          .withSubmitValidator(
              a ->
                  "people".equals(a.kind())
                      ? ValidationResult.success()
                      : ValidationResult.failure(CORRECTION));
  private static final String EVENTS = "{\"kind\":\"events\",\"body\":\"b\"}";
  private static final String PEOPLE = "{\"kind\":\"people\",\"body\":\"b\"}";

  private static AgentSession session(ScriptedModel model, OutputSchema<?> schema, int maxTurns) {
    return AgentSession.create(
        SessionOptions.newBuilder()
            .withModel(model)
            .withSessionId("sess-submit-validator")
            .withClock(CLOCK)
            .withOutputSchema(schema)
            .withLimits(SessionLimits.newBuilder().withMaxTurns(maxTurns).build())
            .build());
  }

  @Test
  void semanticallyInvalidOutputIsCorrectedInsideTheLoop() {
    var model = ScriptedModel.newBuilder().thenText(EVENTS).thenText(PEOPLE).build();
    try (var session = session(model, PEOPLE_ONLY, 5)) {
      var out = session.runBlocking(UserMessage.text("give me people"), PEOPLE_ONLY);

      assertEquals("people", out.kind());
      assertEquals(2, model.calls().size(), "correction turn must be sent to the model");
      var retry = model.calls().get(1);
      var wrongAttempt = retry.get(retry.size() - 2);
      var correction = retry.get(retry.size() - 1);
      assertEquals(Role.ASSISTANT, wrongAttempt.role());
      assertEquals(EVENTS, wrongAttempt.content());
      assertEquals(Role.USER, correction.role());
      assertTrue(correction.content().contains(CORRECTION), correction.content());
    }
  }

  @Test
  void validatorThatNeverPassesTerminatesAtMaxTurns() {
    var model =
        ScriptedModel.newBuilder().thenText(EVENTS).thenText(EVENTS).thenText(EVENTS).build();
    try (var session = session(model, PEOPLE_ONLY, 3)) {
      var terminal = session.runBlocking(UserMessage.text("give me people"));

      assertInstanceOf(ResultMessage.ErrorMaxTurns.class, terminal);
      assertEquals(3, model.calls().size());
    }
  }

  @Test
  void typedRunBlockingNeverReturnsAValueTheValidatorRejects() {
    var model = ScriptedModel.newBuilder().thenText(EVENTS).thenText(EVENTS).build();
    try (var session = session(model, PEOPLE_ONLY, 2)) {
      var ex =
          assertThrows(
              IllegalStateException.class,
              () -> session.runBlocking(UserMessage.text("give me people"), PEOPLE_ONLY));
      assertTrue(ex.getMessage().contains("ErrorMaxTurns"), ex.getMessage());
    }
  }

  @Test
  void typedRunBlockingPostHocParseEnforcesTheValidator() {
    var model = ScriptedModel.newBuilder().thenText(EVENTS).build();
    try (var session = session(model, OutputSchema.of(Answer.class), 5)) {
      var ex =
          assertThrows(
              SubmitValidationException.class,
              () -> session.runBlocking(UserMessage.text("give me people"), PEOPLE_ONLY));
      assertEquals(1, model.calls().size());
      assertTrue(ex.correctionMessage().contains(CORRECTION));
    }
  }
}
