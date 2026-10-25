/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Typed {@code runBlocking} on a session built with an output schema: the session's own schema,
 * built again, parses the answer; any other schema is refused before the model is called.
 */
final class SessionSchemaRunBlockingTest {

  /** The session's output type. */
  public record Verdict(String name, int score) {}

  /** A type the session was not built for. */
  public record Other(String label) {}

  private final ScriptedModel model =
      ScriptedModel.newBuilder().withTextTurn("{\"name\":\"alice\",\"score\":42}").build();

  private AgentSession session() {
    return AgentSession.create(
        SessionOptions.newBuilder()
            .withModel(model)
            .withOutputSchema(OutputSchema.of(Verdict.class))
            .build());
  }

  @Test
  void theSessionsOwnSchemaBuiltAgainParsesTheAnswer() {
    try (var session = session()) {
      assertEquals(
          new Verdict("alice", 42),
          session.runBlocking(UserMessage.text("rate"), OutputSchema.of(Verdict.class)));
    }
  }

  @Test
  void anotherTypeIsRefusedBeforeTheModelIsCalled() {
    try (var session = session()) {
      var refused =
          assertThrows(
              IllegalArgumentException.class,
              () -> session.runBlocking(UserMessage.text("rate"), OutputSchema.of(Other.class)));

      assertEquals(
          "runBlocking schema "
              + Other.class.getName()
              + " differs from the session's output schema "
              + Verdict.class.getName(),
          refused.getMessage());
      assertEquals(List.of(), model.calls());
      assertFalse(session.isStarted());
    }
  }

  @Test
  void theSameTypeWithAnotherJsonSchemaIsRefusedBeforeTheModelIsCalled() {
    var otherShape = OutputSchema.of(Verdict.class, OutputSchema.of(Other.class).schema());
    try (var session = session()) {
      assertThrows(
          IllegalArgumentException.class,
          () -> session.runBlocking(UserMessage.text("rate"), otherShape));

      assertEquals(List.of(), model.calls());
    }
  }
}
