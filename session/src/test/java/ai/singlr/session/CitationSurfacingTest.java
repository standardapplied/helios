/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Citation;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.tool.Tool;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage that grounding citations harvested by the model surface on <em>both</em>
 * session surfaces: the streaming {@link QueryEvent.AssistantCitations} event and the terminal
 * {@link ResultMessage.Success#citations()}. Drives a full {@link AgentSession} against an inline
 * grounded {@link Model} whose {@code chat} returns a {@link Response} carrying citations; the
 * default {@code chatStream} adapter forwards them onto {@link
 * ai.singlr.core.model.ModelChunk.MessageStop}, the loop accumulates them, and they land on the
 * result.
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

    void awaitDone() throws InterruptedException {
      assertTrue(done.await(5, TimeUnit.SECONDS), "event stream did not complete in 5s");
    }
  }

  @Test
  void groundedRunSurfacesCitationsOnEventStreamAndTerminal() throws Exception {
    try (var s = session(groundedModel(List.of(WIKI, BRIT)))) {
      var sub = new CollectingSubscriber();
      s.events().subscribe(sub);
      s.send(UserMessage.text("What is the capital of Australia?"));

      var result = s.result().get(5, TimeUnit.SECONDS);
      sub.awaitDone();

      // Streaming surface.
      var event =
          sub.events.stream()
              .filter(e -> e instanceof QueryEvent.AssistantCitations)
              .map(e -> (QueryEvent.AssistantCitations) e)
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

      var result = s.result().get(5, TimeUnit.SECONDS);
      sub.awaitDone();

      assertTrue(
          sub.events.stream().noneMatch(e -> e instanceof QueryEvent.AssistantCitations),
          "a turn with no grounding must not emit an AssistantCitations event");
      assertTrue(assertInstanceOf(ResultMessage.Success.class, result).citations().isEmpty());
    }
  }
}
