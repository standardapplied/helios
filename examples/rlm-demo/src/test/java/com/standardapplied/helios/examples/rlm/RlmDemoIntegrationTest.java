/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.rlm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.examples.rlm.RlmDemoMain.MatchmakingInput;
import com.standardapplied.helios.examples.rlm.RlmDemoMain.Profile;
import com.standardapplied.helios.examples.rlm.RlmDemoMain.RankedMatches;
import com.standardapplied.helios.gemini.GeminiModelId;
import com.standardapplied.helios.gemini.GeminiProvider;
import com.standardapplied.helios.repl.codeact.CodeActPreset;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
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
