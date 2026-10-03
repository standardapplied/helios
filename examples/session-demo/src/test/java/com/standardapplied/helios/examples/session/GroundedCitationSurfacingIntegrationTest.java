/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.gemini.GeminiModelId;
import com.standardapplied.helios.gemini.GeminiProvider;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * End-to-end proof against the live Gemini Interactions API that grounding citations from a Google
 * Search turn surface on both session surfaces — the streaming {@link
 * QueryEvent.AssistantCitations} event and the terminal {@link ResultMessage.Success#citations()}.
 * This is the exact path the enrichment use case runs: a grounded agent session whose final answer
 * must carry its sources.
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

  private static final class CollectingSubscriber implements Flow.Subscriber<QueryEvent> {
    final List<QueryEvent> events = new ArrayList<>();
    final CountDownLatch done = new CountDownLatch(1);

    @Override
    public void onSubscribe(Flow.Subscription s) {
      s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(QueryEvent e) {
      events.add(e);
    }

    @Override
    public void onError(Throwable t) {
      done.countDown();
    }

    @Override
    public void onComplete() {
      done.countDown();
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
      var sub = new CollectingSubscriber();
      session.events().subscribe(sub);

      var terminal =
          session.runBlocking(
              UserMessage.text(
                  "What were the major AI announcements at Google I/O 2025? Summarize with"
                      + " specific facts and cite where each came from."));

      Await.latch("the event stream to complete", sub.done);

      // Terminal surface — the run's accumulated grounding sources.
      var success = assertInstanceOf(ResultMessage.Success.class, terminal);
      assertFalse(
          success.citations().isEmpty(),
          "grounded session terminal must carry citations — got none");
      success
          .citations()
          .forEach(c -> assertNotNull(c.sourceId(), "every citation must carry a sourceId"));

      // Streaming surface — at least one AssistantCitations event during the run.
      assertTrue(
          sub.events.stream().anyMatch(e -> e instanceof QueryEvent.AssistantCitations),
          "grounded session must emit at least one AssistantCitations event");
    }
  }
}
