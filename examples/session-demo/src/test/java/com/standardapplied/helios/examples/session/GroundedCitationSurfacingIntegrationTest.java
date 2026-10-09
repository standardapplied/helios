/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.gemini.GeminiModelId;
import com.standardapplied.helios.gemini.GeminiProvider;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.test.QuestionAnswers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * End-to-end proof against the live Gemini Interactions API that grounding citations from a Google
 * Search turn surface on both session surfaces — the streaming {@link
 * QueryEvent.AssistantCitations} event and the terminal {@link ResultMessage.Success#citations()}.
 * This is the exact path the enrichment use case runs: a grounded agent session whose final answer
 * must carry its sources. Whether the model searches and cites is its choice; a run without a
 * citation skips, and {@code RecordedSessionTest} replays a recorded grounded session that cites.
 *
 * <p>Guarded by {@code GEMINI_API_KEY} so the suite stays runnable offline.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
final class GroundedCitationSurfacingIntegrationTest {

  private static Model model;

  @BeforeAll
  static void setUp() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey(System.getenv("GEMINI_API_KEY"))
            .withWebSearch(true)
            .build();
    model = new GeminiProvider().create(GeminiModelId.GEMINI_3_5_FLASH.id(), config);
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (model != null) {
      model.close();
    }
  }

  @Test
  void groundedSessionSurfacesCitationsOnEventStreamAndTerminal() throws Exception {
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSystemPrompt(
                "You are a research assistant. Ground every claim with Google Search.")
            .withLimits(SessionLimits.newBuilder().withMaxTurns(2).build())
            .build();

    try (var session = AgentSession.create(options)) {
      var sub = new CollectingSubscriber(QuestionAnswers.selecting(session, "Deny"));
      session.events().subscribe(sub);

      var terminal =
          session.runBlocking(
              UserMessage.text(
                  "What were the major AI announcements at Google I/O 2025? Summarize with"
                      + " specific facts and cite where each came from."));

      sub.awaitDone();

      assertTrue(
          terminal instanceof ResultMessage.Success
              || terminal instanceof ResultMessage.ErrorMaxTurns,
          () -> "ended as " + terminal);
      assertTrue(sub.eventsOf(QueryEvent.Error.class).isEmpty());
      assumeTrue(
          !terminal.citations().isEmpty(),
          () ->
              model.id()
                  + " cited no source; RecordedSessionTest"
                  + "#groundedGeminiTurnSurfacesItsCitations covers the surfacing");
      terminal
          .citations()
          .forEach(c -> assertNotNull(c.sourceId(), "every citation must carry a sourceId"));
      assertFalse(
          sub.eventsOf(QueryEvent.AssistantCitations.class).isEmpty(),
          "grounded session must emit at least one AssistantCitations event");
    }
  }
}
