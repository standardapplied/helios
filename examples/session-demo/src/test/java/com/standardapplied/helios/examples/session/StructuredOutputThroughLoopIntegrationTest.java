/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.gemini.GeminiModelId;
import com.standardapplied.helios.gemini.GeminiProvider;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * End-to-end regression for the wiring that transmits {@link SessionOptions#outputSchema()} to the
 * model on every turn. Before the fix, the agent loop dispatched the untyped {@code
 * Model.chatStream(messages, tools, cancellation)} regardless of whether an output schema was
 * configured, so the schema reached neither {@code response_format.schema} (Gemini) nor a system
 * instruction reinforcement (Anthropic). The model produced freeform text loosely guided by the
 * system prompt, and Helios's post-hoc validator failed against any non-trivial nested schema.
 *
 * <p>These tests run against the live Gemini Interactions API and exercise the same kind of nested
 * schema the original light-grid bug report described — outer record holding a list of inner
 * records with multiple required fields. Gemini constrains the reply to the schema, so the asserts
 * stop at "every required field is present": the framework's job is transmission, and what the
 * model writes in those fields is its choice.
 *
 * <p>Guarded by {@code GEMINI_API_KEY} so the suite stays runnable offline.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
final class StructuredOutputThroughLoopIntegrationTest {

  private static Model model;

  /** Sample inner record matching the matchmaking-style shape with several required fields. */
  public record Recommendation(
      String entityId,
      Double score,
      String connectionThesis,
      String firstAction,
      String rationale,
      List<String> evidence) {}

  /** Outer envelope. */
  public record Recommendations(List<Recommendation> recommendations) {}

  @BeforeAll
  static void setUp() {
    var apiKey = System.getenv("GEMINI_API_KEY");
    var config = ModelConfig.newBuilder().withApiKey(apiKey).build();
    model = new GeminiProvider().create(GeminiModelId.GEMINI_3_5_FLASH.id(), config);
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (model != null) {
      model.close();
    }
  }

  @Test
  void agentLoopTransmitsOutputSchemaAndGeminiReturnsConformingJson() throws Exception {
    var schema = OutputSchema.of(Recommendations.class);
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSystemPrompt(
                "You produce ranked recommendations as JSON. Be concise; one short sentence per"
                    + " field.")
            .withOutputSchema(schema)
            .withLimits(SessionLimits.newBuilder().withMaxTurns(2).build())
            .build();

    try (var session = AgentSession.create(options)) {
      var typed =
          session.runBlocking(
              UserMessage.text(
                  "Rank exactly two of these candidates for someone looking for a hardware-supply"
                      + " advisor: (1) entityId CAND-001, Mario Casiraghi, ex-Tesla Energy"
                      + " Powerwall supply chain, now angel investor in batteries. (2) entityId"
                      + " CAND-003, Henrik Olsen, battery-chemistry PhD, currently in his own"
                      + " fundraise and unavailable for advisory roles. (3) entityId CAND-002,"
                      + " Sara Lin, software-only engineer with no hardware background. Return"
                      + " two recommendations only; include entityId verbatim from this list."),
              schema);

      assertNotNull(typed, "session must deliver a typed Recommendations record");
      assertNotNull(typed.recommendations(), "recommendations list must be non-null");

      for (var rec : typed.recommendations()) {
        // The whole point: every required field present at depth. Pre-fix, fields beyond entityId
        // and rationale were silently dropped because the schema never reached the model.
        assertNotNull(rec.entityId(), "rec must carry entityId; missing in " + rec);
        assertNotNull(rec.score(), "rec must carry score; missing in " + rec);
        assertNotNull(rec.connectionThesis(), "rec must carry connectionThesis; missing in " + rec);
        assertNotNull(rec.firstAction(), "rec must carry firstAction; missing in " + rec);
        assertNotNull(rec.rationale(), "rec must carry rationale; missing in " + rec);
        assertNotNull(rec.evidence(), "rec must carry evidence array; missing in " + rec);
      }
    }
  }

  @Test
  void schemaTransmissionWorksWithNoSystemPrompt() throws Exception {
    // The reporter's matchmaking agent threads its own dense system prompt. This test pins down
    // that the schema transmission path doesn't depend on the system prompt embedding the
    // schema; the schema rides the provider's native channel by itself.
    var schema = OutputSchema.of(Recommendation.class);
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withOutputSchema(schema)
            .withLimits(SessionLimits.newBuilder().withMaxTurns(2).build())
            .build();

    try (var session = AgentSession.create(options)) {
      var rec =
          session.runBlocking(
              UserMessage.text(
                  "Recommend candidate CAND-001 (Mario Casiraghi, ex-Tesla Energy Powerwall"
                      + " supply chain) to someone seeking a hardware-supply-chain advisor. Echo"
                      + " entityId verbatim. Be concise — one short sentence per field."),
              schema);

      assertNotNull(rec.entityId());
      assertNotNull(rec.score());
      assertNotNull(rec.connectionThesis());
      assertNotNull(rec.firstAction());
      assertNotNull(rec.rationale());
      assertNotNull(rec.evidence());
    }
  }
}
