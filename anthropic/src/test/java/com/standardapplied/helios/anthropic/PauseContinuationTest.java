/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.anthropic.api.MessagesRequest;
import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.provider.ChatExchange;
import com.standardapplied.helios.core.provider.Exchange;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.ExchangeTurns;
import com.standardapplied.helios.core.test.RecordingSubscriber;
import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PauseContinuationTest {

  private static final String PAUSED_CONTENT = "[{\"type\":\"server_tool_use\",\"id\":\"s1\"}]";

  private final MessagesRequest request =
      new AnthropicRequestBuilder(
              AnthropicModelId.CLAUDE_OPUS_5_5.id(),
              AnthropicModelId.CLAUDE_OPUS_5_5,
              ModelConfig.newBuilder().withApiKey("k").build(),
              CachePolicy.shortLived())
          .build(List.of(Message.user("go")), List.of(), null);

  @Test
  void aPauseWithoutVerbatimContentCannotResume() {
    var paused = paused(Map.of(AnthropicResponseAssembler.STOP_REASON_KEY, "pause_turn"));

    var error = lastError(r -> stream(paused));

    assertInstanceOf(AnthropicException.class, error.cause());
    assertEquals(
        "pause_turn received without capturable assistant content; cannot resume", error.message());
    assertEquals(FinishReason.ERROR, error.partial().finishReason());
  }

  @Test
  void aPauseWithEmptyVerbatimContentCannotResume() {
    var paused =
        paused(
            Map.of(
                AnthropicResponseAssembler.STOP_REASON_KEY,
                "pause_turn",
                RawContentEcho.RAW_CONTENT_KEY,
                ""));

    assertInstanceOf(AnthropicException.class, lastError(r -> stream(paused)).cause());
  }

  @Test
  void aPauseWithUnreadableVerbatimContentCannotResume() {
    var paused =
        paused(
            Map.of(
                AnthropicResponseAssembler.STOP_REASON_KEY, "pause_turn",
                RawContentEcho.RAW_CONTENT_KEY, "{not json"));

    var error = lastError(r -> stream(paused));

    assertEquals("Failed to decode paused assistant content for resume", error.message());
    assertThrows(AnthropicException.class, () -> turn(r -> stream(paused), request));
  }

  @Test
  void aStreamThatKeepsPausingEndsWithAnErrorAfterTheLimit() {
    var opened = new AtomicInteger();

    var error =
        lastError(
            r -> {
              opened.incrementAndGet();
              return stream(pausedWithContent());
            });

    assertEquals(
        "pause_turn continuation limit exceeded after 8 resumes; the server-tool loop did not"
            + " converge",
        error.message());
    assertNull(error.cause());
    assertEquals(PauseContinuation.MAX_PAUSE_CONTINUATIONS + 1, opened.get());
    assertEquals(FinishReason.ERROR, error.partial().finishReason());
    assertEquals("Searching. ".repeat(9), error.partial().content());
    assertEquals(Response.Usage.of(90, 18), error.partial().usage());
  }

  @Test
  void anErrorInAContinuedSegmentCarriesTheWholeTurnSoFar() {
    var cut =
        Response.newBuilder()
            .withContent("Fou")
            .withFinishReason(FinishReason.ERROR)
            .withUsage(Response.Usage.of(5, 1))
            .build();
    var eof = new EOFException("cut");
    var opened = new AtomicInteger();

    var events =
        events(
            r ->
                opened.getAndIncrement() == 0
                    ? stream(pausedWithContent())
                    : new Events(
                        List.of(
                            new StreamEvent.TextDelta("Fou"),
                            new StreamEvent.Error("cut", eof, cut))));

    assertEquals(new StreamEvent.TextDelta("Fou"), events.getFirst());
    var error = assertInstanceOf(StreamEvent.Error.class, events.getLast());
    assertSame(eof, error.cause());
    assertEquals("Searching. Fou", error.partial().content());
    assertEquals(Response.Usage.of(15, 3), error.partial().usage());
    assertEquals(FinishReason.ERROR, error.partial().finishReason());
  }

  @Test
  void everySegmentIsClosedOnceTheTurnEnds() {
    var paused = stream(pausedWithContent());
    var finished = stream(finished());
    var opened = new AtomicInteger();

    var events = events(r -> opened.getAndIncrement() == 0 ? paused : finished);

    var done = assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals("Searching. Found.", done.response().content());
    Await.latch("the paused segment closed", paused.closed);
    Await.latch("the last segment closed", finished.closed);
  }

  @Test
  void aContinuationTheApiRefusesEndsTheStreamWithItsError() {
    var refused = new AnthropicException("API error (status 400): bad", 400);

    var error =
        resumeFailingWith(
            r -> {
              throw refused;
            });

    assertEquals(refused.getMessage(), error.message());
    assertSame(refused, error.cause());
  }

  @Test
  void aContinuationThatCannotConnectEndsTheStream() {
    var reset = new IOException("reset");

    var error =
        resumeFailingWith(
            r -> {
              throw reset;
            });

    assertEquals("Failed to communicate with Anthropic API", error.message());
    assertSame(reset, error.cause());
  }

  @Test
  void aFirstSegmentThatCannotOpenIsTheStreamsOneError() {
    var refused = new AnthropicException("API error (status 400): bad", 400);

    var events =
        events(
            r -> {
              throw refused;
            });

    var error = assertInstanceOf(StreamEvent.Error.class, events.getFirst());
    assertEquals(1, events.size());
    assertEquals(refused.getMessage(), error.message());
    assertSame(refused, error.cause());
  }

  @Test
  void eventsBeforeThePauseStreamThrough() {
    var text = new StreamEvent.TextDelta("searching");
    var done = new StreamEvent.Done(paused(Map.of()));

    var events = events(r -> new Events(List.of(text, done)));

    assertEquals(List.of(text, done), events);
  }

  @Test
  void thinkingDeltasAcrossSegmentsAddUpToTheMergedThinking() {
    var opened = new AtomicInteger();
    var continued =
        Response.newBuilder()
            .withContent("Found.")
            .withFinishReason(FinishReason.STOP)
            .withThinking("Then found.")
            .withMetadata(Map.of(AnthropicResponseAssembler.STOP_REASON_KEY, "end_turn"))
            .build();

    var events =
        events(
            r ->
                opened.getAndIncrement() == 0
                    ? new Events(
                        List.of(
                            new StreamEvent.ThinkingDelta("Planning."),
                            new StreamEvent.Done(pausedWithContent())))
                    : new Events(
                        List.of(
                            new StreamEvent.ThinkingDelta("Then "),
                            new StreamEvent.ThinkingDelta("found."),
                            new StreamEvent.Done(continued))));

    var thinking = new StringBuilder();
    for (var event : events) {
      if (event instanceof StreamEvent.ThinkingDelta(var text)) {
        thinking.append(text);
      }
    }
    var done = assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals(done.response().thinking(), thinking.toString());
  }

  @Test
  void demandNotYetServedCarriesOverToTheNextSegment() {
    var opened = new AtomicInteger();
    var subscriber = new RecordingSubscriber<StreamEvent>(1);

    continuation(
            r ->
                opened.getAndIncrement() == 0
                    ? new Events(
                        List.of(
                            new StreamEvent.TextDelta("Searching. "),
                            new StreamEvent.Done(pausedWithContent())))
                    : new Events(
                        List.of(
                            new StreamEvent.TextDelta("Found."), new StreamEvent.Done(finished()))))
        .stream(request, new CancellationToken())
        .subscribe(subscriber);
    assertEquals(new StreamEvent.TextDelta("Searching. "), subscriber.next());
    subscriber.request(1);
    assertEquals(new StreamEvent.TextDelta("Found."), subscriber.next());
    subscriber.request(1);

    var events = subscriber.awaitCompletion();
    assertEquals("Searching. Found.", ((StreamEvent.Done) events.getLast()).response().content());
    assertEquals(0, subscriber.violations());
  }

  @Test
  void keepAlivesOnBothSidesOfAHandoffPassThroughOneRequestAtATime() {
    var opened = new AtomicInteger();
    var keepAlive = new StreamEvent.KeepAlive();
    var searching = new StreamEvent.TextDelta("Searching. ");
    var found = new StreamEvent.TextDelta("Found.");
    var subscriber = new RecordingSubscriber<StreamEvent>(1);

    continuation(
            r ->
                opened.getAndIncrement() == 0
                    ? new Events(
                        List.of(
                            keepAlive,
                            searching,
                            keepAlive,
                            new StreamEvent.Done(pausedWithContent())))
                    : new Events(List.of(keepAlive, found, new StreamEvent.Done(finished()))))
        .stream(request, new CancellationToken())
        .subscribe(subscriber);
    for (var i = 0; i < 5; i++) {
      subscriber.next();
      subscriber.request(1);
    }

    var events = subscriber.awaitCompletion();
    assertEquals(List.of(keepAlive, searching, keepAlive, keepAlive, found), events.subList(0, 5));
    assertEquals("Searching. Found.", ((StreamEvent.Done) events.getLast()).response().content());
    assertEquals(6, events.size());
    assertEquals(0, subscriber.violations());
  }

  @Test
  void cancellingCancelsTheSegmentInFlight() {
    var opened = new AtomicInteger();
    var stalled = new Events(List.of(new StreamEvent.TextDelta("Fou")));
    stalled.stalls = true;
    var subscriber = new RecordingSubscriber<StreamEvent>();

    continuation(r -> opened.getAndIncrement() == 0 ? stream(pausedWithContent()) : stalled).stream(
            request, new CancellationToken())
        .subscribe(subscriber);
    assertEquals(new StreamEvent.TextDelta("Fou"), subscriber.next());
    subscriber.cancel();

    Await.latch("the segment in flight closed", stalled.closed);
    assertEquals(2, opened.get());
  }

  @Test
  void cancellingBeforeAPausedSegmentCompletesOpensNoContinuation() {
    var token = new CancellationToken();
    var opened = new AtomicInteger();
    var paused =
        new Events(
            List.of(new StreamEvent.TextDelta("x"), new StreamEvent.Done(pausedWithContent())));
    paused.stalls = true;
    var subscriber = new RecordingSubscriber<StreamEvent>();

    continuation(
            r -> {
              opened.incrementAndGet();
              return paused;
            })
        .stream(request, token)
        .subscribe(subscriber);
    subscriber.next();
    Await.latch("the paused segment stalled", paused.stalled);
    subscriber.cancel();
    Await.latch("the paused segment closed", paused.closed);
    Await.until("the segment's thread finished", () -> token.activeCallbackCountForTests() == 0);

    assertEquals(1, opened.get());
    assertFalse(subscriber.ended());
  }

  @Test
  void cancellingTheTokenDuringAContinuationEndsTheTurn() {
    var token = new CancellationToken();
    var opened = new AtomicInteger();
    var stalled = new Events(List.of());
    stalled.stalls = true;
    var subscriber = new RecordingSubscriber<StreamEvent>();

    continuation(r -> opened.getAndIncrement() == 0 ? stream(pausedWithContent()) : stalled).stream(
            request, token)
        .subscribe(subscriber);
    Await.latch("the continuation stalled", stalled.stalled);
    token.cancel("session closed");

    assertInstanceOf(CancellationException.class, subscriber.awaitFailure());
    Await.latch("the continuation closed", stalled.closed);
  }

  @Test
  void aNonPositiveRequestFailsTheTurn() {
    var subscriber = new RecordingSubscriber<StreamEvent>(0);

    continuation(r -> stream(finished())).stream(request, new CancellationToken())
        .subscribe(subscriber);
    subscriber.subscription().request(0);

    assertInstanceOf(IllegalArgumentException.class, subscriber.awaitFailure());
  }

  @Test
  void aNonPositiveRequestDuringTheHandoffFailsTheContinuationBeforeItOpens() {
    var handoff = new Handoff();
    var subscriber = new RecordingSubscriber<StreamEvent>();

    new PauseContinuation(handoff).stream(request, new CancellationToken()).subscribe(subscriber);
    Await.latch("the continuation handoff", handoff.reached);
    subscriber.subscription().request(0);
    handoff.release.countDown();

    assertInstanceOf(IllegalArgumentException.class, subscriber.awaitFailure());
    assertEquals(1, handoff.opened.get());
    assertEquals(0, subscriber.violations());
  }

  @Test
  void theContinuationIsRequestedOnlyTheDemandTheFirstSegmentLeftUnserved() {
    var handoff = new Handoff();
    handoff.release.countDown();
    var subscriber = new RecordingSubscriber<StreamEvent>(2);

    new PauseContinuation(handoff).stream(request, new CancellationToken()).subscribe(subscriber);

    assertEquals(
        "request 1", Await.next("the continuation's first call", handoff.continuationCalls));
  }

  @Test
  void cancellingDuringTheHandoffCancelsTheContinuationWithoutARequest() {
    var handoff = new Handoff();
    var subscriber = new RecordingSubscriber<StreamEvent>();

    new PauseContinuation(handoff).stream(request, new CancellationToken()).subscribe(subscriber);
    Await.latch("the continuation handoff", handoff.reached);
    subscriber.cancel();
    handoff.release.countDown();

    assertEquals("cancel", Await.next("the continuation's first call", handoff.continuationCalls));
  }

  @Test
  void cancellingTheTokenDuringTheHandoffEndsTheTurnWithoutOpeningTheContinuation() {
    var token = new CancellationToken();
    var handoff = new Handoff();
    var subscriber = new RecordingSubscriber<StreamEvent>();

    new PauseContinuation(handoff).stream(request, token).subscribe(subscriber);
    Await.latch("the continuation handoff", handoff.reached);
    token.cancel("session closed");
    handoff.release.countDown();

    assertInstanceOf(CancellationException.class, subscriber.awaitFailure());
    assertEquals(1, handoff.opened.get());
  }

  @Test
  void aRequestAfterANonPositiveOneDuringTheHandoffStillFailsTheContinuation() {
    var handoff = new Handoff();
    var subscriber = new RecordingSubscriber<StreamEvent>();

    new PauseContinuation(handoff).stream(request, new CancellationToken()).subscribe(subscriber);
    Await.latch("the continuation handoff", handoff.reached);
    subscriber.subscription().request(0);
    subscriber.request(1);
    handoff.release.countDown();

    assertInstanceOf(IllegalArgumentException.class, subscriber.awaitFailure());
    assertEquals(1, handoff.opened.get());
  }

  @Test
  void aSegmentThatEndsWithoutTheDemandItsDoneNeededLeavesTheNextOneUnrequested() {
    var continued = new ChatExchange<MessagesRequest>("Anthropic API", r -> stream(finished()));
    var subscriber = new RecordingSubscriber<StreamEvent>(1);

    new PauseContinuation(
            (r, t) ->
                r == request
                    ? pushedRegardlessOfDemand(pausedWithContent())
                    : continued.stream(r, t))
        .stream(request, new CancellationToken()).subscribe(subscriber);
    subscriber.request(1);

    var done = (StreamEvent.Done) subscriber.awaitCompletion().getLast();
    assertEquals("Searching. Found.", done.response().content());
  }

  @Test
  void aCancelBetweenAPausedSegmentsDoneAndItsCompletionOpensNoContinuation() {
    var streams = new AtomicInteger();
    var subscriber = new RecordingSubscriber<StreamEvent>();
    var segment = new AtomicReference<Flow.Subscriber<? super StreamEvent>>();

    new PauseContinuation(
            (r, t) -> {
              streams.incrementAndGet();
              return events -> {
                segment.set(events);
                events.onSubscribe(new NoSubscription());
              };
            })
        .stream(request, new CancellationToken()).subscribe(subscriber);
    segment.get().onNext(new StreamEvent.Done(pausedWithContent()));
    subscriber.cancel();
    segment.get().onComplete();

    assertEquals(1, streams.get());
    assertFalse(subscriber.ended());
  }

  @Test
  void aSecondSubscriberIsRefused() {
    var publisher = continuation(r -> stream(finished())).stream(request, new CancellationToken());
    var first = new RecordingSubscriber<StreamEvent>();
    var second = new RecordingSubscriber<StreamEvent>();

    publisher.subscribe(first);
    publisher.subscribe(second);

    assertInstanceOf(IllegalStateException.class, second.awaitFailure());
    assertInstanceOf(StreamEvent.Done.class, first.awaitCompletion().getLast());
    assertThrows(NullPointerException.class, () -> publisher.subscribe(null));
  }

  private StreamEvent.Error resumeFailingWith(ChatExchange.StreamOpener<MessagesRequest> resume) {
    var opened = new AtomicInteger();
    var error =
        lastError(
            r -> opened.getAndIncrement() == 0 ? stream(pausedWithContent()) : resume.open(r));
    assertEquals(FinishReason.ERROR, error.partial().finishReason());
    assertEquals("Searching. ", error.partial().content());
    return error;
  }

  private StreamEvent.Error lastError(ChatExchange.StreamOpener<MessagesRequest> segments) {
    return assertInstanceOf(StreamEvent.Error.class, events(segments).getLast());
  }

  private List<StreamEvent> events(ChatExchange.StreamOpener<MessagesRequest> segments) {
    var subscriber = new RecordingSubscriber<StreamEvent>();
    continuation(segments).stream(request, new CancellationToken()).subscribe(subscriber);
    return subscriber.awaitCompletion();
  }

  static PauseContinuation continuation(ChatExchange.StreamOpener<MessagesRequest> segments) {
    return new PauseContinuation(new ChatExchange<>("Anthropic API", segments));
  }

  /**
   * The response of the whole turn {@code segments} answer {@code request} with, drained the way
   * the provider's model drains it; a failed turn throws the exception the model would.
   */
  static Response<Void> turn(
      ChatExchange.StreamOpener<MessagesRequest> segments, MessagesRequest request) {
    return ExchangeTurns.chat(
        continuation(segments), request, AnthropicProvider.PROVIDER_NAME, AnthropicException::new);
  }

  private static Response<Void> finished() {
    return Response.newBuilder()
        .withContent("Found.")
        .withFinishReason(FinishReason.STOP)
        .withUsage(Response.Usage.of(5, 1))
        .withMetadata(Map.of(AnthropicResponseAssembler.STOP_REASON_KEY, "end_turn"))
        .build();
  }

  private static Response<Void> pausedWithContent() {
    return paused(FinishReason.STOP);
  }

  private static Response<Void> paused(FinishReason finishReason) {
    return Response.newBuilder()
        .withContent("Searching. ")
        .withToolCalls(List.of(ToolCall.newBuilder().withId("t1").withName("lookup").build()))
        .withFinishReason(finishReason)
        .withUsage(Response.Usage.of(10, 2))
        .withThinking("Planning.")
        .withCitations(List.of(Citation.of("https://example.com", "snippet")))
        .withMetadata(
            Map.of(
                AnthropicResponseAssembler.STOP_REASON_KEY,
                "pause_turn",
                RawContentEcho.RAW_CONTENT_KEY,
                PAUSED_CONTENT))
        .build();
  }

  private static Response<Void> paused(Map<String, String> metadata) {
    return Response.newBuilder()
        .withContent("")
        .withFinishReason(FinishReason.STOP)
        .withMetadata(metadata)
        .build();
  }

  private static Events stream(Response<Void> response) {
    return new Events(List.of(new StreamEvent.Done(response)));
  }

  /** A segment that sends its text and its {@code Done} at once, whatever was requested. */
  private static Flow.Publisher<StreamEvent> pushedRegardlessOfDemand(Response<Void> paused) {
    return events -> {
      events.onSubscribe(new NoSubscription());
      events.onNext(new StreamEvent.TextDelta("Searching. "));
      events.onNext(new StreamEvent.Done(paused));
      events.onComplete();
    };
  }

  /** A subscription that ignores every request and cancel. */
  private static final class NoSubscription implements Flow.Subscription {
    @Override
    public void request(long n) {
      // Ignored: the segment pushes what it has.
    }

    @Override
    public void cancel() {
      // Ignored: the segment pushes what it has.
    }
  }

  /**
   * Segments whose continuation is held at its opening until released: the first segment streams
   * one text delta and pauses, the second finishes and records the first call made on its
   * subscription.
   */
  private static final class Handoff implements Exchange<MessagesRequest> {
    private final AtomicInteger opened = new AtomicInteger();
    private final ChatExchange<MessagesRequest> segments =
        new ChatExchange<>(
            "Anthropic API",
            r ->
                opened.getAndIncrement() == 0
                    ? new Events(
                        List.of(
                            new StreamEvent.TextDelta("Searching. "),
                            new StreamEvent.Done(pausedWithContent())))
                    : PauseContinuationTest.stream(finished()));
    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final LinkedBlockingQueue<String> continuationCalls = new LinkedBlockingQueue<>();
    private final AtomicInteger streams = new AtomicInteger();

    @Override
    public Flow.Publisher<StreamEvent> stream(MessagesRequest r, CancellationToken token) {
      var events = segments.stream(r, token);
      if (streams.getAndIncrement() == 0) {
        return events;
      }
      reached.countDown();
      Await.latch("the handoff released", release);
      return subscriber -> events.subscribe(new Recorded(subscriber));
    }

    private final class Recorded implements Flow.Subscriber<StreamEvent> {
      private final Flow.Subscriber<? super StreamEvent> downstream;

      Recorded(Flow.Subscriber<? super StreamEvent> downstream) {
        this.downstream = downstream;
      }

      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        downstream.onSubscribe(
            new Flow.Subscription() {
              @Override
              public void request(long n) {
                continuationCalls.add("request " + n);
                subscription.request(n);
              }

              @Override
              public void cancel() {
                continuationCalls.add("cancel");
                subscription.cancel();
              }
            });
      }

      @Override
      public void onNext(StreamEvent event) {
        downstream.onNext(event);
      }

      @Override
      public void onError(Throwable failure) {
        downstream.onError(failure);
      }

      @Override
      public void onComplete() {
        downstream.onComplete();
      }
    }
  }

  /** Recorded events; one that stalls blocks after its last event until closed. */
  private static final class Events implements CloseableIterator<StreamEvent> {
    private final List<StreamEvent> events;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final CountDownLatch stalled = new CountDownLatch(1);
    private int next;
    private boolean stalls;

    Events(List<StreamEvent> events) {
      this.events = events;
    }

    @Override
    public boolean hasNext() {
      if (stalls && next == events.size()) {
        stalled.countDown();
        try {
          closed.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return false;
      }
      return next < events.size();
    }

    @Override
    public StreamEvent next() {
      return events.get(next++);
    }

    @Override
    public void close() {
      closed.countDown();
    }
  }
}
