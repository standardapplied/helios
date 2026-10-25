/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

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
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.schema.StructuredContentParser;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.StreamRead;
import com.standardapplied.helios.core.tool.Tool;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class StreamingModelTest {

  public record Answer(String summary) {}

  /** A request a test provider builds: what it was built from. */
  record Request(List<Message> messages, List<Tool> tools, Map<String, Object> outputSchema) {}

  private static final StructuredContentParser.JsonAdapter ANSWERS =
      new JsonBinding(
          json -> {
            if (!json.startsWith("{")) {
              throw new IllegalArgumentException("not a JSON object");
            }
            return Map.of("summary", json.replaceAll("\\W", "").replace("summary", ""));
          },
          (map, type) -> new Answer((String) map.get("summary")));

  private static final List<Message> MESSAGES = List.of(Message.user("hi"));

  private final List<Request> chatted = new CopyOnWriteArrayList<>();
  private Response<Void> reply = done("{\"summary\":\"ok\"}");
  private List<StreamEvent> events;
  private RuntimeException failure;
  private Error fatal;
  private StreamEvent afterClose;
  private boolean blockAtEnd;
  private volatile Recorded opened;

  @Test
  void chatDrainsTheBuiltRequestsStreamToTheResponseOfItsCompletion() {
    var response = model(config()).chat(MESSAGES, List.of());

    assertEquals(reply, response);
    assertEquals(List.of(new Request(MESSAGES, List.of(), null)), chatted);
    assertTrue(opened.closed());
  }

  @Test
  void chatThrowsTheProvidersOwnExceptionFromAnErrorEvent() {
    var own = new AcmeException("boom", 503, null);

    assertSame(own, assertThrows(AcmeException.class, () -> chatOn(error("x", own))));
  }

  @Test
  void aTransientFailureIsWhatChatThrowsAndWhatChatStreamThrows() {
    var status = "API error (status 529): busy";
    var overloaded =
        new TransientStreamException(status, new AcmeException(status, 529, null), "acme");
    events = List.of(error(status, overloaded));

    assertSame(
        overloaded,
        assertThrows(
            TransientStreamException.class, () -> model(config()).chat(MESSAGES, List.of())));
    assertSame(overloaded, streamed().failure());
  }

  @Test
  void chatTurnsAReadFailureIntoARetryableFailureNamingTheProvider() {
    var reset = new IOException("reset");

    var thrown =
        assertThrows(
            TransientStreamException.class, () -> chatOn(error("Stream read error", reset)));

    assertEquals("Stream read error", thrown.getMessage());
    assertSame(reset, thrown.getCause());
    assertEquals("acme", thrown.providerName());
  }

  @Test
  void chatWrapsAnyOtherFailureInTheProvidersException() {
    var parse = new IllegalArgumentException("bad json");

    var wrapped = assertThrows(AcmeException.class, () -> chatOn(error("Failed to parse", parse)));
    var bare = assertThrows(AcmeException.class, () -> chatOn(error("API error", null)));

    assertEquals("Failed to parse", wrapped.getMessage());
    assertSame(parse, wrapped.getCause());
    assertEquals("API error", bare.getMessage());
    assertNull(bare.getCause());
  }

  @Test
  void chatFailsAStreamThatEndsWithoutItsTerminalEvent() {
    var thrown = assertThrows(AcmeException.class, () -> chatOn(new StreamEvent.TextDelta("x")));

    assertEquals("Stream ended without completion event", thrown.getMessage());
    assertTrue(opened.closed());
  }

  @Test
  void chatThrowsAnUncheckedOrErrorFailureOfTheStreamAsItCameAndClosesIt() {
    var cancelled = new CancellationException("stop");
    var overflow = new StackOverflowError();

    failure = cancelled;
    var unchecked = assertThrows(CancellationException.class, () -> chatOn());
    var uncheckedStream = opened;
    failure = null;
    fatal = overflow;
    var error = assertThrows(StackOverflowError.class, () -> chatOn());

    assertSame(cancelled, unchecked);
    assertSame(overflow, error);
    assertTrue(uncheckedStream.closed());
    assertTrue(opened.closed());
  }

  @Test
  void chatStreamYieldsEachEventAsItsChunk() {
    var call = ToolCall.newBuilder().withId("c1").withName("lookup").build();
    var cite = Citation.of("https://example.com", "snippet");
    reply =
        Response.newBuilder()
            .withContent("Hi")
            .withToolCalls(List.of(call))
            .withFinishReason(FinishReason.TOOL_CALLS)
            .withUsage(Response.Usage.of(3, 2))
            .withMetadata(Map.of("k", "v"))
            .withCitations(List.of(cite))
            .build();
    events =
        List.of(
            new StreamEvent.ThinkingDelta("hm"),
            new StreamEvent.KeepAlive(),
            new StreamEvent.TextDelta("Hi"),
            new StreamEvent.ToolCallStart("c1", "lookup"),
            new StreamEvent.ToolCallDelta("c1", "{}"),
            new StreamEvent.ToolCallComplete(call),
            new StreamEvent.Done(reply));

    var stream = model(config()).chatStream(MESSAGES, List.of());
    var sentBeforeRead = List.copyOf(chatted);
    var chunks = StreamRead.of(stream).items();

    assertEquals(
        List.of(
            new ModelChunk.ThinkingDelta("hm"),
            new ModelChunk.KeepAlive(),
            new ModelChunk.TextDelta("Hi"),
            new ModelChunk.ToolUseStart("c1", "lookup"),
            new ModelChunk.ToolUseDelta("c1", "{}"),
            new ModelChunk.ToolUseStop(call),
            new ModelChunk.MessageStop(
                "TOOL_CALLS", Response.Usage.of(3, 2), Map.of("k", "v"), List.of(cite))),
        chunks);
    assertEquals(List.of(new Request(MESSAGES, List.of(), null)), sentBeforeRead);
  }

  @Test
  void theMessageStopEndsTheStreamAndClosesTheEvents() {
    events = List.of(new StreamEvent.Done(reply), new StreamEvent.TextDelta("late"));
    var stream = model(config()).chatStream(MESSAGES, List.of());

    assertInstanceOf(ModelChunk.MessageStop.class, stream.next());

    assertFalse(stream.hasNext());
    assertThrows(NoSuchElementException.class, stream::next);
    assertTrue(opened.closed());
  }

  @Test
  void chatStreamThrowsAFailedTurnsExceptionAfterWhatStreamedAndClosesTheEvents() {
    var reset = new IOException("reset");
    events =
        List.of(
            new StreamEvent.TextDelta("Hel"),
            error("Stream read error", reset),
            new StreamEvent.TextDelta("lo"));

    var stream = model(config()).chatStream(MESSAGES, List.of());

    assertEquals(new ModelChunk.TextDelta("Hel"), stream.next());
    var thrown = assertThrows(TransientStreamException.class, stream::hasNext);
    assertSame(reset, thrown.getCause());
    assertFalse(stream.hasNext());
    assertTrue(opened.closed());
  }

  @Test
  void chatStreamFailsAStreamThatEndsWithoutItsTerminalEvent() {
    events = List.of(new StreamEvent.TextDelta("Hel"));

    var thrown = assertInstanceOf(AcmeException.class, streamed().failure());

    assertEquals("Stream ended without completion event", thrown.getMessage());
  }

  @Test
  void chatStreamFailsAnEventItCannotTurnIntoAChunkAndClosesTheEvents() {
    events = List.of(new StreamEvent.ToolCallStart(" ", "lookup"));

    assertInstanceOf(IllegalArgumentException.class, streamed().failure());
    assertTrue(opened.closed());
  }

  @Test
  void chatStreamPassesTheStreamsOwnFailureThrough() {
    var cancelled = new CancellationException("stop");
    events = List.of();
    failure = cancelled;

    assertSame(cancelled, streamed().failure());
  }

  @Test
  void closingTheChunkStreamClosesTheEventsAndABlockedReadThrowsCancellation() {
    events = List.of(new StreamEvent.TextDelta("Hel"));
    blockAtEnd = true;
    var stream = model(config()).chatStream(MESSAGES, List.of());
    stream.next();
    try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
      var read = reader.submit(stream::hasNext);
      Await.latch("the read blocked", opened.blocked);

      stream.close();

      var cancelled =
          assertInstanceOf(CancellationException.class, Await.failure("the read", read));
      assertEquals("Stream ended without completion event", cancelled.getCause().getMessage());
      assertTrue(opened.closed());
    }
  }

  @Test
  void anErrorEventTheEventsYieldAfterTheCloseIsThrownAsCancellation() {
    events = List.of();
    blockAtEnd = true;
    afterClose = error("Stream read error", new IOException("Stream closed"));
    var stream = model(config()).chatStream(MESSAGES, List.of());
    try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
      var read = reader.submit(stream::hasNext);
      Await.until("the stream opened", () -> opened != null);
      Await.latch("the read blocked", opened.blocked);

      stream.close();

      var cancelled =
          assertInstanceOf(CancellationException.class, Await.failure("the read", read));
      assertInstanceOf(TransientStreamException.class, cancelled.getCause());
    }
  }

  @Test
  void anEventABlockedReadYieldsAfterTheCloseIsThrownAsCancellation() {
    var streams = new ArrayList<CloseableIterator<ModelChunk>>();
    CloseableIterator<StreamEvent> parsing =
        new CloseableIterator<>() {
          @Override
          public boolean hasNext() {
            return true;
          }

          @Override
          public StreamEvent next() {
            streams.getFirst().close();
            return new StreamEvent.TextDelta("after close");
          }

          @Override
          public void close() {}
        };
    var stream =
        parts(config()).withExchange(request -> parsing).build().chatStream(MESSAGES, List.of());
    streams.add(stream);

    assertThrows(CancellationException.class, stream::hasNext);
    assertFalse(stream.hasNext());
  }

  @Test
  void aRetryableErrorReadWhileTheReaderIsInterruptedIsThrownAsCancellationAndKeepsTheInterrupt() {
    var reset = new IOException("Connection reset");
    var interrupting = new InterruptingEvents(error("Stream read error", reset));
    var stream =
        parts(config())
            .withExchange(request -> interrupting)
            .build()
            .chatStream(MESSAGES, List.of());

    var cancelled = interruptedRead(stream);

    assertInstanceOf(TransientStreamException.class, cancelled.getCause());
    assertTrue(interrupting.closed());
    assertFalse(stream.hasNext());
  }

  @Test
  void aChunkReadWhileTheReaderIsInterruptedIsThrownAsCancellationAndKeepsTheInterrupt() {
    var interrupting = new InterruptingEvents(new StreamEvent.TextDelta("read while interrupted"));
    var stream =
        parts(config())
            .withExchange(request -> interrupting)
            .build()
            .chatStream(MESSAGES, List.of());

    var cancelled = interruptedRead(stream);

    assertNull(cancelled.getCause());
    assertTrue(interrupting.closed());
    assertFalse(stream.hasNext());
  }

  /** Reads {@code stream}, which ends interrupted, and asserts the read kept the interrupt. */
  private static CancellationException interruptedRead(CloseableIterator<ModelChunk> stream) {
    CancellationException cancelled;
    boolean interruptKept;
    try {
      cancelled = assertThrows(CancellationException.class, stream::hasNext);
    } finally {
      interruptKept = Thread.interrupted();
    }
    assertTrue(interruptKept);
    return cancelled;
  }

  /** Events whose one read is interrupted while it parses, as another thread's interrupt would. */
  private static final class InterruptingEvents implements CloseableIterator<StreamEvent> {
    private final StreamEvent event;
    private boolean read;
    private volatile boolean closed;

    InterruptingEvents(StreamEvent event) {
      this.event = event;
    }

    @Override
    public boolean hasNext() {
      return !read;
    }

    @Override
    public StreamEvent next() {
      read = true;
      Thread.currentThread().interrupt();
      return event;
    }

    @Override
    public void close() {
      closed = true;
    }

    boolean closed() {
      return closed;
    }
  }

  @Test
  void aCancellationTheEventsThrowAfterTheCloseIsThrownAsItself() {
    var awaiting = new CountDownLatch(1);
    var closing = new CountDownLatch(1);
    var cancelled = new CancellationException("closed while awaiting the headers");
    CloseableIterator<StreamEvent> awaitingHeaders =
        new CloseableIterator<>() {
          @Override
          public boolean hasNext() {
            awaiting.countDown();
            Await.latch("the close", closing);
            throw cancelled;
          }

          @Override
          public StreamEvent next() {
            throw new NoSuchElementException();
          }

          @Override
          public void close() {
            closing.countDown();
          }
        };
    var stream =
        parts(config())
            .withExchange(request -> awaitingHeaders)
            .build()
            .chatStream(MESSAGES, List.of());
    try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
      var read = reader.submit(stream::hasNext);
      Await.latch("the read awaits the headers", awaiting);

      stream.close();

      assertSame(cancelled, Await.failure("the read", read));
    }
  }

  @Test
  void anInterruptAfterAChunkWasReadAheadEndsTheStreamClosesItsEventsAndKeepsTheInterrupt() {
    events = List.of(new StreamEvent.TextDelta("first"));
    var stream = model(config()).chatStream(MESSAGES, List.of());
    assertTrue(stream.hasNext());

    Thread.currentThread().interrupt();
    boolean interruptKept;
    try {
      assertThrows(CancellationException.class, stream::hasNext);
    } finally {
      interruptKept = Thread.interrupted();
    }

    assertTrue(interruptKept);
    assertTrue(opened.closed());
    assertFalse(stream.hasNext());
  }

  @Test
  void aCloseAfterAChunkWasReadAheadEndsTheStream() {
    events = List.of(new StreamEvent.TextDelta("first"));
    var stream = model(config()).chatStream(MESSAGES, List.of());
    assertTrue(stream.hasNext());

    stream.close();

    assertThrows(CancellationException.class, stream::next);
    assertFalse(stream.hasNext());
  }

  @Test
  void anInterruptOnceTheMessageStopWasReadLeavesTheStreamEnded() {
    var stream = model(config()).chatStream(MESSAGES, List.of());
    while (stream.hasNext()) {
      stream.next();
    }

    Thread.currentThread().interrupt();
    boolean ended;
    try {
      ended = !stream.hasNext();
    } finally {
      Thread.interrupted();
    }

    assertTrue(ended);
  }

  @Test
  void aStructuredStreamAsksForTheSchemaAndThrowsAMismatchAfterTheText() {
    var schema = OutputSchema.of(Answer.class);
    reply = done("not json");

    var read = StreamRead.of(model(config()).chatStream(MESSAGES, List.of(), schema));

    assertInstanceOf(StructuredOutputParseException.class, read.failure());
    assertEquals(List.of(new ModelChunk.TextDelta("not json")), read.items());
    assertEquals(schema.schema().toMap(), chatted.getFirst().outputSchema());
  }

  @Test
  void aStructuredStreamCompletesATurnThatMatchesOrCalledTools() {
    var schema = OutputSchema.of(Answer.class);
    var call = ToolCall.newBuilder().withId("c").withName("t").build();
    var calling =
        Response.newBuilder()
            .withContent("Looking.")
            .withToolCalls(List.of(call))
            .withFinishReason(FinishReason.TOOL_CALLS)
            .build();

    var matching = StreamRead.of(model(config()).chatStream(MESSAGES, List.of(), schema));
    reply = calling;
    var toolTurn = StreamRead.of(model(config()).chatStream(MESSAGES, List.of(), schema));

    assertInstanceOf(ModelChunk.MessageStop.class, matching.items().getLast());
    assertInstanceOf(ModelChunk.MessageStop.class, toolTurn.items().getLast());
  }

  @Test
  void structuredChatAsksForTheSchemaAndParsesATurnWithoutToolCalls() {
    var schema = OutputSchema.of(Answer.class);

    var response = model(config()).chat(MESSAGES, List.of(), schema);

    assertEquals(new Answer("ok"), response.parsed());
    assertEquals(reply.content(), response.content());
    assertEquals(FinishReason.STOP, response.finishReason());
    assertEquals(schema.schema().toMap(), chatted.getFirst().outputSchema());
  }

  @Test
  void structuredChatLeavesATurnThatCalledToolsUnparsed() {
    var call = ToolCall.newBuilder().withId("c").withName("t").build();
    reply =
        Response.newBuilder()
            .withContent("Let me look that up.")
            .withToolCalls(List.of(call))
            .withFinishReason(FinishReason.TOOL_CALLS)
            .build();

    var response = model(config()).chat(MESSAGES, List.of(), OutputSchema.of(Answer.class));

    assertNull(response.parsed());
    assertEquals(List.of(call), response.toolCalls());
  }

  @Test
  void structuredChatKeepsTheRawOutputOfAFailedParseOnlyWhenTheConfigurationCapturesIt() {
    reply = done("not json");
    var schema = OutputSchema.of(Answer.class);

    var captured =
        assertThrows(
            StructuredOutputParseException.class,
            () -> model(config()).chat(MESSAGES, List.of(), schema));
    var withheld =
        assertThrows(
            StructuredOutputParseException.class,
            () ->
                model(
                        ModelConfig.newBuilder()
                            .withRawOutputCapture(RawOutputCapturePolicy.DISABLED)
                            .build())
                    .chat(MESSAGES, List.of(), schema));

    assertEquals("not json", captured.rawContent());
    assertNull(withheld.rawContent());
  }

  @Test
  void identityComesFromTheBuilderAndTheConfiguration() {
    var model = model(config());

    assertEquals("acme-1", model.id());
    assertEquals("acme", model.provider());
    assertEquals(200_000, model.contextWindow());
    assertEquals(8_000, model.maxOutputTokens());
    assertEquals(RawOutputCapturePolicy.ENABLED, model.rawOutputCapturePolicy());
  }

  @Test
  void aConfiguredContextWindowOverridesTheModelsOwn() {
    var model = model(ModelConfig.newBuilder().withContextWindow(50_000).build());

    assertEquals(50_000, model.contextWindow());
  }

  @Test
  void tokenCountsDefaultToUnknown() {
    var model = parts(config()).build();

    assertEquals(0, model.contextWindow());
    assertEquals(0, model.maxOutputTokens());
  }

  @Test
  void closeShutsTheHttpClientDown() {
    var client = HttpClientFactory.create();

    parts(config()).withHttpClient(client).build().close();

    assertTrue(client.isTerminated());
  }

  @Test
  void aBlankIdOrProviderFailsTheBuild() {
    var noId =
        assertThrows(IllegalArgumentException.class, () -> parts(config()).withId(" ").build());
    var noProvider =
        assertThrows(
            IllegalArgumentException.class, () -> parts(config()).withProvider(null).build());

    assertEquals("id must not be blank", noId.getMessage());
    assertEquals("provider must not be blank", noProvider.getMessage());
  }

  @Test
  void aNegativeTokenCountFailsTheBuild() {
    var window =
        assertThrows(
            IllegalArgumentException.class,
            () -> parts(config()).withDefaultContextWindow(-1).build());
    var output =
        assertThrows(
            IllegalArgumentException.class, () -> parts(config()).withMaxOutputTokens(-1).build());

    assertEquals("contextWindow must not be negative: -1", window.getMessage());
    assertEquals("maxOutputTokens must not be negative: -1", output.getMessage());
  }

  @Test
  void aMissingPartFailsTheBuildNamingIt() {
    assertEquals("config must not be null", missing(parts(config()).withConfig(null)).getMessage());
    assertEquals(
        "httpClient must not be null", missing(parts(config()).withHttpClient(null)).getMessage());
    assertEquals(
        "requests must not be null", missing(parts(config()).withRequests(null)).getMessage());
    assertEquals(
        "exchange must not be null", missing(parts(config()).withExchange(null)).getMessage());
    assertEquals("json must not be null", missing(parts(config()).withJson(null)).getMessage());
    assertEquals(
        "failure must not be null", missing(parts(config()).withFailure(null)).getMessage());
  }

  private StreamingModel<Request> model(ModelConfig config) {
    return parts(config).withDefaultContextWindow(200_000).withMaxOutputTokens(8_000).build();
  }

  private StreamingModel.Builder<Request> parts(ModelConfig config) {
    return StreamingModel.<Request>newBuilder()
        .withId("acme-1")
        .withProvider("acme")
        .withConfig(config)
        .withHttpClient(HttpClientFactory.create())
        .withRequests(Request::new)
        .withExchange(new RecordingExchange())
        .withFailure(AcmeException::new)
        .withJson(ANSWERS);
  }

  private static ModelConfig config() {
    return ModelConfig.newBuilder().build();
  }

  private static NullPointerException missing(StreamingModel.Builder<Request> builder) {
    return assertThrows(NullPointerException.class, builder::build);
  }

  private static Response<Void> done(String content) {
    return Response.newBuilder()
        .withContent(content)
        .withFinishReason(FinishReason.STOP)
        .withUsage(Response.Usage.of(2, 1))
        .build();
  }

  private void chatOn(StreamEvent... turn) {
    events = List.of(turn);
    model(config()).chat(MESSAGES, List.of());
  }

  private StreamRead<ModelChunk> streamed() {
    return StreamRead.of(model(config()).chatStream(MESSAGES, List.of()));
  }

  private static StreamEvent.Error error(String message, Exception cause) {
    return new StreamEvent.Error(
        message, cause, Response.newBuilder().withFinishReason(FinishReason.ERROR).build());
  }

  /** The events of a completed {@code response}: its text, its tool calls, then its completion. */
  private static List<StreamEvent> turn(Response<Void> response) {
    var turn = new ArrayList<StreamEvent>();
    if (!response.content().isEmpty()) {
      turn.add(new StreamEvent.TextDelta(response.content()));
    }
    response.toolCalls().forEach(call -> turn.add(new StreamEvent.ToolCallComplete(call)));
    turn.add(new StreamEvent.Done(response));
    return turn;
  }

  /** The exception a test provider throws, so a pass-through is told apart from a wrap. */
  static final class AcmeException extends ProviderException {
    AcmeException(String message, int statusCode, Throwable cause) {
      super(message, statusCode, cause);
    }
  }

  /**
   * Recorded events, then the configured failure; a stream that blocks at its end until closed, and
   * then yields {@code afterClose} if set.
   */
  private static final class Recorded implements CloseableIterator<StreamEvent> {
    private final List<StreamEvent> events;
    private final RuntimeException failure;
    private final Error fatal;
    private final boolean blocks;
    private StreamEvent afterClose;
    private final CountDownLatch blocked = new CountDownLatch(1);
    private final CountDownLatch closing = new CountDownLatch(1);
    private int next;

    Recorded(
        List<StreamEvent> events,
        RuntimeException failure,
        Error fatal,
        boolean blocks,
        StreamEvent afterClose) {
      this.events = events;
      this.failure = failure;
      this.fatal = fatal;
      this.blocks = blocks;
      this.afterClose = afterClose;
    }

    @Override
    public boolean hasNext() {
      if (next < events.size()) {
        return true;
      }
      if (failure != null) {
        throw failure;
      }
      if (fatal != null) {
        throw fatal;
      }
      if (blocks) {
        blocked.countDown();
        Await.latch("the stream closed", closing);
      }
      return afterClose != null;
    }

    @Override
    public StreamEvent next() {
      if (next < events.size()) {
        return events.get(next++);
      }
      var last = afterClose;
      afterClose = null;
      return last;
    }

    @Override
    public void close() {
      closing.countDown();
    }

    boolean closed() {
      return closing.getCount() == 0;
    }
  }

  /** Records each request and answers it with the configured events, or else the reply's turn. */
  private final class RecordingExchange implements Exchange<Request> {

    @Override
    public CloseableIterator<StreamEvent> stream(Request request) {
      chatted.add(request);
      var turn = events != null ? events : turn(reply);
      opened = new Recorded(turn, failure, fatal, blockAtEnd, afterClose);
      return opened;
    }
  }
}
