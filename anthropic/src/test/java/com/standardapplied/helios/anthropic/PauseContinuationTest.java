/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.provider.Exchange;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.ExchangeTurns;
import com.standardapplied.helios.core.test.HeldSseServer;
import com.standardapplied.helios.core.test.StreamRead;
import com.standardapplied.helios.core.test.StubHttpServer;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PauseContinuationTest {

  private static final String PAUSED_CONTENT = "[{\"type\":\"server_tool_use\",\"id\":\"s1\"}]";

  private static final Map<String, String> PAUSED_METADATA =
      Map.of(
          AnthropicResponseAssembler.STOP_REASON_KEY,
          "pause_turn",
          RawContentEcho.RAW_CONTENT_KEY,
          PAUSED_CONTENT);

  private static final String PAUSED_SSE =
      """
      event: message_start
      data: {"type":"message_start","message":{"id":"m1","type":"message","role":"assistant","content":[],"model":"claude-opus-5-5","stop_reason":null,"usage":{"input_tokens":10,"output_tokens":1}}}

      event: content_block_start
      data: {"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srv1","name":"web_search"}}

      event: content_block_stop
      data: {"type":"content_block_stop","index":0}

      event: message_delta
      data: {"type":"message_delta","delta":{"stop_reason":"pause_turn","stop_sequence":null},"usage":{"output_tokens":4}}

      event: message_stop
      data: {"type":"message_stop"}

      """;

  private static final String CONTINUED_HEAD =
      """
      event: message_start
      data: {"type":"message_start","message":{"id":"m2","type":"message","role":"assistant","content":[],"model":"claude-opus-5-5","stop_reason":null,"usage":{"input_tokens":20,"output_tokens":1}}}

      event: content_block_start
      data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

      event: content_block_delta
      data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Fou"}}

      """;

  private final StampedRequest request =
      new AnthropicRequestBuilder(
              AnthropicModelId.CLAUDE_OPUS_5_5.id(),
              AnthropicModelId.CLAUDE_OPUS_5_5,
              ModelConfig.newBuilder().withApiKey("k").build(),
              CachePolicy.shortLived())
          .build(List.of(Message.user("go")), List.of(), null);

  @Test
  void aPauseWithoutVerbatimContentCannotResumeAndEndsWithTheTurnSoFar() {
    var paused =
        paused("Planning.", Map.of(AnthropicResponseAssembler.STOP_REASON_KEY, "pause_turn"));

    var error = lastError(r -> stream(paused));

    assertInstanceOf(AnthropicException.class, error.cause());
    assertEquals(
        "pause_turn received without capturable assistant content; cannot resume", error.message());
    assertEquals(FinishReason.ERROR, error.partial().finishReason());
    assertEquals("Searching. ", error.partial().content());
    assertEquals(Response.Usage.of(10, 2), error.partial().usage());
    assertEquals("Planning.", error.partial().thinking());
    assertEquals(paused.toolCalls(), error.partial().toolCalls());
    assertEquals(paused.citations(), error.partial().citations());
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
  void pausedSegmentsAreSplicedIntoOneDoneAndEachIsClosedBeforeTheNextOpens() {
    var paused = stream(pausedWithContent());
    var finished = stream(finished());
    var pausedClosedAtSecondOpen = new AtomicReference<Boolean>();
    var opened = new AtomicInteger();

    var events =
        events(
            r -> {
              if (opened.getAndIncrement() == 0) {
                return paused;
              }
              pausedClosedAtSecondOpen.set(paused.isClosed());
              return finished;
            });

    var done = assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals(1, events.size());
    assertEquals("Searching. Found.", done.response().content());
    assertEquals(Response.Usage.of(15, 3), done.response().usage());
    assertEquals(FinishReason.TOOL_CALLS, done.response().finishReason());
    assertTrue(pausedClosedAtSecondOpen.get());
    assertTrue(finished.isClosed());
  }

  @Test
  void theContinuationResendsTheRequestWithThePausedContentAppended() {
    var sent = new ArrayList<StampedRequest>();

    events(
        r -> {
          sent.add(r);
          return stream(sent.size() == 1 ? pausedWithContent() : finished());
        });

    assertSame(request, sent.getFirst());
    assertEquals(request.promptStamp(), sent.get(1).promptStamp());
    var resent = sent.get(1).body().messages();
    assertEquals(request.body().messages(), resent.subList(0, resent.size() - 1));
    assertEquals("assistant", resent.getLast().role());
  }

  @Test
  void aContinuationTheApiRefusesEndsTheStreamWithItsError() {
    var refused = new AnthropicException("API error (status 400): bad", 400);

    var error = resumeFailingWith(r -> failedToOpen(refused));

    assertEquals(refused.getMessage(), error.message());
    assertSame(refused, error.cause());
  }

  @Test
  void aContinuationAnsweredOverloadedEndsTheStreamInOneTransientError() throws IOException {
    var overloaded = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}";
    try (var server =
            StubHttpServer.start(
                InetAddress.getLoopbackAddress(),
                0,
                r -> new StubHttpServer.Reply(529, Map.of(), overloaded));
        var client = HttpClientFactory.create()) {
      var config = ModelConfig.newBuilder().withBaseUrl(server.uri().toString()).build();
      var streams = new AnthropicStreams(config, client, List.of());

      var error = resumeFailingWith(streams::open);

      var transientFailure = assertInstanceOf(TransientStreamException.class, error.cause());
      var cause = assertInstanceOf(AnthropicException.class, transientFailure.getCause());
      assertEquals(529, cause.statusCode());
      assertEquals("API error (status 529): " + overloaded, error.message());
      assertEquals(1, server.requests().size());
    }
  }

  @Test
  void aContinuationThatCannotConnectEndsTheStream() throws IOException {
    String closedPort;
    try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      closedPort = "http://127.0.0.1:" + socket.getLocalPort();
    }
    try (var client = HttpClientFactory.create()) {
      var config = ModelConfig.newBuilder().withBaseUrl(closedPort).build();
      var streams = new AnthropicStreams(config, client, List.of());

      var error = resumeFailingWith(streams::open);

      assertEquals("Failed to communicate with Anthropic API", error.message());
      assertInstanceOf(IOException.class, error.cause());
    }
  }

  @Test
  void aFirstSegmentThatCannotOpenIsTheStreamsOneError() {
    var refused = failedToOpen(new AnthropicException("API error (status 400): bad", 400));
    var failure = refused.events.getFirst();

    var events = events(r -> refused);

    assertEquals(List.of(failure), events);
    assertSame(failure, events.getFirst());
    assertTrue(refused.isClosed());
  }

  @Test
  void eventsBeforeThePauseStreamThrough() {
    var text = new StreamEvent.TextDelta("searching");
    var done = new StreamEvent.Done(paused(Map.of()));

    var events = events(r -> new Events(List.of(text, done)));

    assertEquals(List.of(text, done), events);
  }

  @Test
  void eventsOnBothSidesOfAPauseStreamThroughInOrder() {
    var keepAlive = new StreamEvent.KeepAlive();
    var searching = new StreamEvent.TextDelta("Searching. ");
    var found = new StreamEvent.TextDelta("Found.");
    var opened = new AtomicInteger();

    var events =
        events(
            r ->
                opened.getAndIncrement() == 0
                    ? new Events(
                        List.of(
                            keepAlive,
                            searching,
                            keepAlive,
                            new StreamEvent.Done(pausedWithContent())))
                    : new Events(List.of(keepAlive, found, new StreamEvent.Done(finished()))));

    assertEquals(List.of(keepAlive, searching, keepAlive, keepAlive, found), events.subList(0, 5));
    assertEquals(6, events.size());
    assertEquals("Searching. Found.", ((StreamEvent.Done) events.getLast()).response().content());
  }

  @Test
  void theContinuationsFirstThinkingDeltaCarriesTheSeparatorSoTheDeltasAddUpToTheMergedThinking() {
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

    assertEquals(
        List.of(
            new StreamEvent.ThinkingDelta("Planning."),
            new StreamEvent.ThinkingDelta(ThinkingBlocks.SEPARATOR + "Then "),
            new StreamEvent.ThinkingDelta("found.")),
        events.subList(0, 3));
    var done = assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals(
        "Planning." + ThinkingBlocks.SEPARATOR + "Then found.", done.response().thinking());
  }

  @Test
  void aContinuationsThinkingAfterAPauseWithoutThinkingCarriesNoSeparator() {
    var opened = new AtomicInteger();
    var unthinking = paused(null, PAUSED_METADATA);
    var thinking = new StreamEvent.ThinkingDelta("Then.");

    var events =
        events(
            r ->
                opened.getAndIncrement() == 0
                    ? stream(unthinking)
                    : new Events(List.of(thinking, new StreamEvent.Done(finished()))));

    assertEquals(thinking, events.getFirst());
  }

  @Test
  void nextAfterTheTerminalThrowsNoSuchElement() {
    var turn = new PauseContinuation(r -> stream(finished())).stream(request);

    assertInstanceOf(StreamEvent.Done.class, turn.next());
    assertFalse(turn.hasNext());
    assertThrows(NoSuchElementException.class, turn::next);
  }

  @Test
  void closingClosesTheSegmentInFlightAndEndsABlockedRead() throws Exception {
    var opened = new AtomicInteger();
    var stalled = new Events(List.of(new StreamEvent.TextDelta("Fou")));
    stalled.stalls = true;
    var turn =
        new PauseContinuation(
                r -> opened.getAndIncrement() == 0 ? stream(pausedWithContent()) : stalled)
            .stream(request);
    assertEquals(new StreamEvent.TextDelta("Fou"), turn.next());

    try (var reading = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocked = reading.submit(turn::hasNext);
      Await.latch("the continuation stalled", stalled.stalled);
      turn.close();

      assertFalse(Await.value("the blocked read", blocked));
    }
    assertTrue(stalled.isClosed());
    assertEquals(2, opened.get());
  }

  @Test
  void closingDuringTheSecondSegmentClosesItsConnectionAndOpensNoThird() throws Exception {
    var paused = HeldSseServer.Reply.of(PAUSED_SSE);
    var continued = HeldSseServer.Reply.of(CONTINUED_HEAD, PAUSED_SSE);
    try (var server = HeldSseServer.start(paused, continued);
        var client = HttpClientFactory.create()) {
      var config = ModelConfig.newBuilder().withBaseUrl(server.uri().toString()).build();
      var turn = new PauseContinuation(new AnthropicStreams(config, client, List.of())::open);
      var events = turn.stream(request);
      StreamEvent event;
      do {
        event = events.next();
      } while (!(event instanceof StreamEvent.TextDelta));
      assertEquals(new StreamEvent.TextDelta("Fou"), event);

      events.close();

      continued.awaitClientClosed();
      assertFalse(events.hasNext());
      assertEquals(2, server.requests());
    }
  }

  @Test
  void aCloseBetweenSegmentsLeavesTheContinuationUnsentAndThrowsCancellation() {
    var turn = new AtomicReference<CloseableIterator<StreamEvent>>();
    var paused = stream(pausedWithContent());
    paused.onClose = () -> turn.get().close();
    var continuation = stream(finished());
    var opened = new AtomicInteger();
    turn.set(
        new PauseContinuation(r -> opened.getAndIncrement() == 0 ? paused : continuation)
            .stream(request));

    assertThrows(CancellationException.class, turn.get()::hasNext);

    assertEquals(2, opened.get());
    assertTrue(continuation.isClosed());
    assertFalse(continuation.read);
  }

  private StreamEvent.Error resumeFailingWith(Exchange<StampedRequest> resume) {
    var opened = new AtomicInteger();
    var error =
        lastError(
            r -> opened.getAndIncrement() == 0 ? stream(pausedWithContent()) : resume.stream(r));
    assertEquals(FinishReason.ERROR, error.partial().finishReason());
    assertEquals("Searching. ", error.partial().content());
    return error;
  }

  private StreamEvent.Error lastError(Exchange<StampedRequest> segments) {
    var events = events(segments);
    assertEquals(1, events.stream().filter(StreamEvent.Error.class::isInstance).count());
    return assertInstanceOf(StreamEvent.Error.class, events.getLast());
  }

  private List<StreamEvent> events(Exchange<StampedRequest> segments) {
    var read = StreamRead.of(new PauseContinuation(segments).stream(request));
    assertNull(read.failure());
    return read.items();
  }

  /**
   * The response of the whole turn {@code segments} answer {@code request} with, drained the way
   * the provider's model drains it; a failed turn throws the exception the model would.
   */
  static Response<Void> turn(Exchange<StampedRequest> segments, StampedRequest request) {
    return ExchangeTurns.chat(
        new PauseContinuation(segments),
        request,
        AnthropicProvider.PROVIDER_NAME,
        AnthropicException::new);
  }

  private static Events failedToOpen(Exception cause) {
    var nothing =
        Response.newBuilder().withContent("").withFinishReason(FinishReason.ERROR).build();
    return new Events(List.of(new StreamEvent.Error(cause.getMessage(), cause, nothing)));
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
    return paused("Planning.", PAUSED_METADATA);
  }

  private static Response<Void> paused(String thinking, Map<String, String> metadata) {
    return Response.newBuilder()
        .withContent("Searching. ")
        .withToolCalls(List.of(ToolCall.newBuilder().withId("t1").withName("lookup").build()))
        .withFinishReason(FinishReason.STOP)
        .withUsage(Response.Usage.of(10, 2))
        .withThinking(thinking)
        .withCitations(List.of(Citation.of("https://example.com", "snippet")))
        .withMetadata(metadata)
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

  /** Recorded events; one that stalls blocks after its last event until closed. */
  private static final class Events implements CloseableIterator<StreamEvent> {
    private final List<StreamEvent> events;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final CountDownLatch stalled = new CountDownLatch(1);
    private int next;
    private boolean stalls;
    private boolean read;
    private Runnable onClose = () -> {};

    Events(List<StreamEvent> events) {
      this.events = events;
    }

    @Override
    public boolean hasNext() {
      read = true;
      if (stalls && next == events.size()) {
        stalled.countDown();
        Await.latch("the stalled segment closed", closed);
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
      if (closed.getCount() > 0) {
        closed.countDown();
        onClose.run();
      }
    }

    boolean isClosed() {
      return closed.getCount() == 0;
    }
  }
}
