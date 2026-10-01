/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */
package ai.singlr.examples.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.gemini.GeminiModelId;
import ai.singlr.gemini.GeminiProvider;
import ai.singlr.session.AgentSession;
import ai.singlr.session.SessionLimits;
import ai.singlr.session.SessionOptions;
import ai.singlr.session.UserMessage;
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
 * records with multiple required fields. The asserts deliberately stop at "every required field is
 * present" rather than checking semantic correctness, because the framework's job is transmission,
 * not content quality.
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
      assertEquals(
          2,
          typed.recommendations().size(),
          "model returned a different rec count: " + typed.recommendations().size());

      for (var rec : typed.recommendations()) {
        // The whole point: every required field present at depth. Pre-fix, fields beyond entityId
        // and rationale were silently dropped because the schema never reached the model.
        assertNotNull(rec.entityId(), "rec must carry entityId; missing in " + rec);
        assertNotNull(rec.score(), "rec must carry score; missing in " + rec);
        assertNotNull(rec.connectionThesis(), "rec must carry connectionThesis; missing in " + rec);
        assertNotNull(rec.firstAction(), "rec must carry firstAction; missing in " + rec);
        assertNotNull(rec.rationale(), "rec must carry rationale; missing in " + rec);
        assertNotNull(rec.evidence(), "rec must carry evidence array; missing in " + rec);
        assertFalse(
            rec.evidence().isEmpty(), "evidence array must be non-empty for rec " + rec.entityId());
        assertTrue(
            rec.entityId().startsWith("CAND-"),
            "model must echo entityId verbatim from the prompt corpus, got: " + rec.entityId());
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

      assertEquals("CAND-001", rec.entityId());
      assertNotNull(rec.score());
      assertNotNull(rec.connectionThesis());
      assertNotNull(rec.firstAction());
      assertNotNull(rec.rationale());
      assertNotNull(rec.evidence());
      assertFalse(rec.evidence().isEmpty());
    }
  }
}
