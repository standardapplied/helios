/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage that grounding citations harvested by the model surface on <em>both</em>
 * session surfaces: the streaming {@link QueryEvent.AssistantCitations} event and the terminal
 * {@link ResultMessage.Success#citations()}. Drives a full {@link AgentSession} against an inline
 * grounded {@link Model} whose {@code chat} returns a {@link Response} carrying citations; the
 * default {@code chatStream} adapter forwards them onto {@link
 * com.standardapplied.helios.core.model.ModelChunk.MessageStop}, the loop accumulates them, and
 * they land on the result.
 */
final class CitationSurfacingTest {

  private static final String SID = "sess-citations";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-06-10T19:00:00Z"), ZoneOffset.UTC);

  private static final Citation WIKI =
      Citation.newBuilder()
          .withSourceId("https://en.wikipedia.org/x")
          .withTitle("wikipedia.org")
          .build();
  private static final Citation BRIT =
      Citation.newBuilder()
          .withSourceId("https://britannica.com/y")
          .withTitle("britannica.com")
          .build();

  private static Model groundedModel(List<Citation> citations) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder()
            .withContent("Canberra is the capital of Australia.")
            .withFinishReason(FinishReason.STOP)
            .withUsage(Usage.of(12, 8))
            .withCitations(citations)
            .build();
      }

      @Override
      public String id() {
        return "test";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  private static AgentSession session(Model model) {
    return AgentSession.create(
        SessionOptions.newBuilder().withModel(model).withSessionId(SID).withClock(CLOCK).build());
  }

  @Test
  void groundedRunSurfacesCitationsOnEventStreamAndTerminal() throws Exception {
    try (var s = session(groundedModel(List.of(WIKI, BRIT)))) {
      var sub = new CollectingSubscriber();
      s.events().subscribe(sub);
      s.send(UserMessage.text("What is the capital of Australia?"));

      var result = Await.value("the session result", s.result());
      sub.awaitDone();

      // Streaming surface.
      var event =
          sub.eventsOf(QueryEvent.AssistantCitations.class).stream()
              .findFirst()
              .orElseThrow(() -> new AssertionError("expected an AssistantCitations event"));
      assertEquals(List.of(WIKI, BRIT), event.citations());

      // Terminal surface.
      var success = assertInstanceOf(ResultMessage.Success.class, result);
      assertEquals(List.of(WIKI, BRIT), success.citations());
    }
  }

  @Test
  void ungroundedRunEmitsNoCitationEventAndEmptyTerminalCitations() throws Exception {
    try (var s = session(groundedModel(List.of()))) {
      var sub = new CollectingSubscriber();
      s.events().subscribe(sub);
      s.send(UserMessage.text("hi"));

      var result = Await.value("the session result", s.result());
      sub.awaitDone();

      assertTrue(
          sub.eventsOf(QueryEvent.AssistantCitations.class).isEmpty(),
          "a turn with no grounding must not emit an AssistantCitations event");
      assertTrue(assertInstanceOf(ResultMessage.Success.class, result).citations().isEmpty());
    }
  }
}
