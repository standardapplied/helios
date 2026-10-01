/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */
package ai.singlr.examples.rlm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.examples.rlm.RlmDemoMain.MatchmakingInput;
import ai.singlr.examples.rlm.RlmDemoMain.Profile;
import ai.singlr.examples.rlm.RlmDemoMain.RankedMatches;
import ai.singlr.gemini.GeminiModelId;
import ai.singlr.gemini.GeminiProvider;
import ai.singlr.repl.codeact.CodeActPreset;
import ai.singlr.session.AgentSession;
import ai.singlr.session.SessionLimits;
import ai.singlr.session.SessionOptions;
import ai.singlr.session.UserMessage;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Integration test for the RLM demo against Gemini. Skipped when {@code GEMINI_API_KEY} is unset.
 *
 * <p>Assertions describe the framework guarantee given a cooperating model — the typed {@code
 * runBlocking} path returns a non-null {@link RankedMatches} with at least one match. Specific
 * candidate ordering is not asserted: Flash is not fully deterministic on free-form code synthesis.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
final class RlmDemoIntegrationTest {

  private static Model mainModel;
  private static Model subModel;

  @BeforeAll
  static void setUp() {
    var apiKey = System.getenv("GEMINI_API_KEY");
    var config = ModelConfig.newBuilder().withApiKey(apiKey).build();
    var provider = new GeminiProvider();
    mainModel = provider.create(GeminiModelId.GEMINI_3_5_FLASH.id(), config);
    subModel = provider.create(GeminiModelId.GEMINI_3_5_FLASH.id(), config);
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (mainModel != null) {
      mainModel.close();
    }
    if (subModel != null) {
      subModel.close();
    }
  }

  @Test
  void demoProducesRankedMatches() throws Exception {
    var input =
        new MatchmakingInput(
            "Senior backend engineer for a real-time payments platform. Must know the JVM deeply"
                + " and have shipped low-latency distributed systems.",
            List.of(
                new Profile(
                    "Alice",
                    "Staff engineer with 12 years of Java. Built an order-matching engine."
                        + " Obsessed with p99 latency."),
                new Profile(
                    "Bob",
                    "Frontend specialist with 8 years of React. Recently picked up Node.js."),
                new Profile(
                    "Carla",
                    "Principal engineer with 15 years on the JVM. Led a payments-ledger rewrite"
                        + " at a fintech unicorn."),
                new Profile(
                    "Dan",
                    "ML researcher with 4 years at a model-training lab. Minimal production-"
                        + "systems exposure.")));

    var options =
        SessionOptions.newBuilder()
            .withModel(mainModel)
            .apply(
                CodeActPreset.withSubLm(
                    MatchmakingInput.class, RankedMatches.class, input, subModel))
            .withLimits(
                SessionLimits.newBuilder()
                    .withMaxTurns(10)
                    .withToolTimeoutDefault(Duration.ofMinutes(2))
                    .build())
            .build();

    try (var session = AgentSession.create(options)) {
      var prompt =
          "Rank these candidates against `criteria`. In a single Execute(JSHELL) call: fan out"
              + " predict(\"Score this candidate from 0 to 100. Reply with ONLY an integer.\","
              + " criteria + \"\\n\\n\" + c.bio()) for every candidate, parse the integer, pick"
              + " the top 3 by score, and call submit(java.util.Map.of(\"matches\","
              + " java.util.List.of(java.util.Map.of(\"name\", ..., \"score\", ..., \"reasoning\","
              + " ...), ...))) with the ranked matches.";
      var ranked =
          session.runBlocking(UserMessage.text(prompt), OutputSchema.of(RankedMatches.class));

      assertNotNull(ranked, "typed runBlocking should produce a non-null RankedMatches");
      assertNotNull(ranked.matches(), "matches list should not be null");
      assertFalse(ranked.matches().isEmpty(), "ranked matches should contain at least one entry");
      ranked
          .matches()
          .forEach(
              m -> {
                assertNotNull(m.name(), "match name should be set");
                assertFalse(m.name().isBlank(), "match name should be non-blank");
                assertTrue(
                    m.score() >= 0 && m.score() <= 100,
                    () -> "match score should be in 0..100, got " + m.score());
              });
    }
  }
}
