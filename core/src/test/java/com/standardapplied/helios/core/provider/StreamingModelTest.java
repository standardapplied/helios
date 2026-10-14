/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.IteratorPublisher;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.schema.StructuredContentParser;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.RecordingSubscriber;
import com.standardapplied.helios.core.tool.Tool;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
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
  private Flow.Publisher<StreamEvent> publisher;
  private volatile ChatExchangeTest.Recorded opened;
  private boolean blockAtEnd;

  @Test
  void chatDrainsTheBuiltRequestsStreamToItsCompletionsResponse() {
    events = List.of(new StreamEvent.TextDelta("{"), new StreamEvent.Done(reply));

    var response = model(config()).chat(MESSAGES, List.of());

    assertSame(reply, response);
    assertEquals(List.of(new Request(MESSAGES, List.of(), null)), chatted);
  }

  @Test
  void chatThrowsTheProvidersOwnExceptionFromAnErrorEvent() {
    var own = new AcmeException("boom", 503, null);

    assertSame(own, assertThrows(AcmeException.class, () -> chatOn(error("x", own))));
  }

  @Test
  void chatThrowsATransientFailureFromAnErrorEvent() {
    var transientFailure = new TransientStreamException("overloaded", null, "acme");

    assertSame(
        transientFailure,
        assertThrows(TransientStreamException.class, () -> chatOn(error("x", transientFailure))));
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
  }

  @Test
  void chatThrowsAnUncheckedOrErrorFailureOfTheStreamAsItCame() {
    var cancelled = new CancellationException("stop");
    var fatal = new StackOverflowError();

    publisher = failingWith(cancelled);
    var unchecked = assertThrows(CancellationException.class, () -> chatOn());
    publisher = failingWith(fatal);
    var error = assertThrows(StackOverflowError.class, () -> chatOn());

    assertSame(cancelled, unchecked);
    assertSame(fatal, error);
  }

  @Test
  void chatWrapsACheckedFailureOfTheStreamInTheProvidersException() {
    var checked = new IOException("closed");
    publisher = failingWith(checked);

    var thrown = assertThrows(AcmeException.class, () -> chatOn());

    assertEquals("Stream failed", thrown.getMessage());
    assertSame(checked, thrown.getCause());
  }

  @Test
  void anInterruptWhileChattingCancelsTheStreamAndRestoresTheInterrupt() {
    events = List.of();
    blockAtEnd = true;
    var model = model(config());
    var interruptRestored = new boolean[1];
    var chat =
        new FutureTask<>(
            () -> {
              try {
                return model.chat(MESSAGES, List.of());
              } finally {
                interruptRestored[0] = Thread.currentThread().isInterrupted();
              }
            });
    var caller = Thread.ofPlatform().start(chat);
    Await.until("the stream opened", () -> opened != null);
    Await.latch("the read blocked", opened.blocked);
    caller.interrupt();

    var thrown = assertInstanceOf(AcmeException.class, Await.failure("the chat", chat));
    assertEquals("Request interrupted", thrown.getMessage());
    Await.latch("the stream closed", opened.closed);
    assertTrue(interruptRestored[0]);
  }

  @Test
  void anInterruptBeforeTheStreamSubscribesCancelsTheLateSubscriptionWithoutARequest() {
    var subscribers = new LinkedBlockingQueue<Flow.Subscriber<? super StreamEvent>>();
    publisher = subscribers::add;
    var model = model(config());
    var interruptRestored = new boolean[1];
    var chat =
        new FutureTask<>(
            () -> {
              try {
                return model.chat(MESSAGES, List.of());
              } finally {
                interruptRestored[0] = Thread.currentThread().isInterrupted();
              }
            });
    var caller = Thread.ofPlatform().start(chat);
    var late = Await.next("the chat subscribed", subscribers);
    caller.interrupt();
    var thrown = assertInstanceOf(AcmeException.class, Await.failure("the chat", chat));
    assertEquals("Request interrupted", thrown.getMessage());
    assertTrue(interruptRestored[0]);

    var requests = new AtomicInteger();
    var cancels = new AtomicInteger();
    late.onSubscribe(
        new Flow.Subscription() {
          @Override
          public void request(long n) {
            requests.incrementAndGet();
          }

          @Override
          public void cancel() {
            cancels.incrementAndGet();
          }
        });

    assertEquals(0, requests.get());
    assertEquals(1, cancels.get());
  }

  @Test
  void anInterruptWhoseCancelFailsStillRestoresTheInterrupt() {
    var subscribed = new CountDownLatch(1);
    publisher =
        subscriber ->
            subscriber.onSubscribe(
                new Flow.Subscription() {
                  @Override
                  public void request(long n) {
                    subscribed.countDown();
                  }

                  @Override
                  public void cancel() {
                    throw new IllegalStateException("close failed");
                  }
                });
    var model = model(config());
    var interruptRestored = new boolean[1];
    var chat =
        new FutureTask<>(
            () -> {
              try {
                return model.chat(MESSAGES, List.of());
              } finally {
                interruptRestored[0] = Thread.currentThread().isInterrupted();
              }
            });
    var caller = Thread.ofPlatform().start(chat);
    Await.latch("the chat subscribed", subscribed);
    caller.interrupt();

    var thrown = assertInstanceOf(IllegalStateException.class, Await.failure("the chat", chat));
    assertEquals("close failed", thrown.getMessage());
    assertTrue(interruptRestored[0]);
  }

  @Test
  void chatStreamPublishesEachEventAsItsChunkWithinDemand() {
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
            new StreamEvent.ThinkingComplete("hm", "sig"),
            new StreamEvent.TextDelta("Hi"),
            new StreamEvent.ToolCallStart("c1", "lookup"),
            new StreamEvent.ToolCallDelta("c1", "{}"),
            new StreamEvent.ToolCallComplete(call),
            new StreamEvent.Done(reply));
    var subscriber = new RecordingSubscriber<ModelChunk>(1);

    model(config()).chatStream(MESSAGES, List.of(), new CancellationToken()).subscribe(subscriber);
    for (var i = 0; i < 6; i++) {
      subscriber.next();
      subscriber.request(1);
    }

    assertEquals(
        List.of(
            new ModelChunk.ThinkingDelta("hm"),
            new ModelChunk.TextDelta("Hi"),
            new ModelChunk.ToolUseStart("c1", "lookup"),
            new ModelChunk.ToolUseDelta("c1", "{}"),
            new ModelChunk.ToolUseStop(call),
            new ModelChunk.MessageStop(
                "TOOL_CALLS", Response.Usage.of(3, 2), Map.of("k", "v"), List.of(cite))),
        subscriber.awaitCompletion());
    assertEquals(0, subscriber.violations());
    assertEquals(List.of(new Request(MESSAGES, List.of(), null)), chatted);
  }

  @Test
  void chatStreamEndsAFailedTurnWithItsExceptionAndIgnoresWhatFollows() {
    var reset = new IOException("reset");
    events =
        List.of(
            new StreamEvent.TextDelta("Hel"),
            error("Stream read error", reset),
            new StreamEvent.TextDelta("lo"));

    var subscriber = streamed(model(config()));

    var thrown = assertInstanceOf(TransientStreamException.class, subscriber.awaitFailure());
    assertSame(reset, thrown.getCause());
    assertEquals(List.of(new ModelChunk.TextDelta("Hel")), subscriber.items());
    Await.latch("the stream closed", opened.closed);
    assertEquals(0, subscriber.violations());
  }

  @Test
  void chatStreamIgnoresWhatAnEventStreamSendsAfterItFailedTheTurn() {
    var reset = new IOException("reset");
    publisher =
        subscriber ->
            subscriber.onSubscribe(
                new Flow.Subscription() {
                  @Override
                  public void request(long n) {
                    subscriber.onNext(error("Stream read error", reset));
                    subscriber.onNext(new StreamEvent.TextDelta("late"));
                    subscriber.onError(new IllegalStateException("late"));
                    subscriber.onComplete();
                  }

                  @Override
                  public void cancel() {}
                });

    var subscriber = streamed(model(config()));

    assertSame(reset, subscriber.awaitFailure().getCause());
    assertEquals(List.of(), subscriber.items());
    assertEquals(0, subscriber.violations());
  }

  @Test
  void chatStreamEndsAFailedTurnWithItsExceptionEvenWhenCancellingTheStreamThrows() {
    var reset = new IOException("reset");
    var escaped = new CompletableFuture<Throwable>();
    publisher =
        subscriber ->
            subscriber.onSubscribe(
                new Flow.Subscription() {
                  @Override
                  public void request(long n) {
                    Thread.ofVirtual()
                        .start(
                            () -> {
                              try {
                                subscriber.onNext(error("Stream read error", reset));
                              } catch (IllegalStateException thrown) {
                                escaped.complete(thrown);
                              }
                            });
                  }

                  @Override
                  public void cancel() {
                    throw new IllegalStateException("close failed");
                  }
                });

    var subscriber = streamed(model(config()));

    var thrown = assertInstanceOf(TransientStreamException.class, subscriber.awaitFailure());
    assertSame(reset, thrown.getCause());
    assertEquals("close failed", Await.value("the cancel's failure", escaped).getMessage());
    assertEquals(0, subscriber.violations());
  }

  @Test
  void chatStreamFailsAStreamThatCompletesWithoutItsTerminalEvent() {
    events = List.of(new StreamEvent.TextDelta("Hel"));

    var thrown = assertInstanceOf(AcmeException.class, streamed(model(config())).awaitFailure());

    assertEquals("Stream ended without completion event", thrown.getMessage());
  }

  @Test
  void chatStreamFailsAnEventItCannotTurnIntoAChunkAndCancelsTheStream() {
    events = List.of(new StreamEvent.ToolCallStart(" ", "lookup"));
    blockAtEnd = true;

    var subscriber = streamed(model(config()));

    assertInstanceOf(IllegalArgumentException.class, subscriber.awaitFailure());
    Await.latch("the stream closed", blockedStream().closed);
  }

  @Test
  void chatStreamPassesTheStreamsOwnFailureThrough() {
    var cancelled = new CancellationException("stop");
    publisher = failingWith(cancelled);

    assertSame(cancelled, streamed(model(config())).awaitFailure());
  }

  @Test
  void cancellingTheChunkSubscriptionCancelsTheEventStream() {
    events = List.of(new StreamEvent.TextDelta("Hel"));
    blockAtEnd = true;
    var subscriber = new RecordingSubscriber<ModelChunk>();

    model(config()).chatStream(MESSAGES, List.of(), new CancellationToken()).subscribe(subscriber);
    subscriber.next();
    Await.latch("the read blocked", blockedStream().blocked);
    subscriber.cancel();

    Await.latch("the stream closed", opened.closed);
  }

  @Test
  void aStructuredStreamAsksForTheSchemaAndChecksTheCompletedTurn() {
    var schema = OutputSchema.of(Answer.class);
    reply = done("not json");
    events = List.of(new StreamEvent.TextDelta("not json"), new StreamEvent.Done(reply));
    var subscriber = new RecordingSubscriber<ModelChunk>();

    model(config())
        .chatStream(MESSAGES, List.of(), schema, new CancellationToken())
        .subscribe(subscriber);

    assertInstanceOf(StructuredOutputParseException.class, subscriber.awaitFailure());
    assertEquals(List.of(new ModelChunk.TextDelta("not json")), subscriber.items());
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

    events = List.of(new StreamEvent.Done(reply));
    var matching = new RecordingSubscriber<ModelChunk>();
    model(config())
        .chatStream(MESSAGES, List.of(), schema, new CancellationToken())
        .subscribe(matching);
    events = List.of(new StreamEvent.Done(calling));
    var toolTurn = new RecordingSubscriber<ModelChunk>();
    model(config())
        .chatStream(MESSAGES, List.of(), schema, new CancellationToken())
        .subscribe(toolTurn);

    assertInstanceOf(ModelChunk.MessageStop.class, matching.awaitCompletion().getLast());
    assertInstanceOf(ModelChunk.MessageStop.class, toolTurn.awaitCompletion().getLast());
  }

  @Test
  void chatStreamRejectsANullToken() {
    var model = model(config());
    var schema = OutputSchema.of(Answer.class);

    assertThrows(NullPointerException.class, () -> model.chatStream(MESSAGES, List.of(), null));
    assertThrows(
        NullPointerException.class, () -> model.chatStream(MESSAGES, List.of(), schema, null));
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
    return Response.newBuilder().withContent(content).withFinishReason(FinishReason.STOP).build();
  }

  private void chatOn(StreamEvent... turn) {
    events = List.of(turn);
    model(config()).chat(MESSAGES, List.of());
  }

  private RecordingSubscriber<ModelChunk> streamed(StreamingModel<Request> model) {
    var subscriber = new RecordingSubscriber<ModelChunk>();
    model.chatStream(MESSAGES, List.of(), new CancellationToken()).subscribe(subscriber);
    return subscriber;
  }

  private ChatExchangeTest.Recorded blockedStream() {
    Await.until("the stream opened", () -> opened != null);
    return opened;
  }

  private static StreamEvent.Error error(String message, Exception cause) {
    return new StreamEvent.Error(
        message, cause, Response.newBuilder().withFinishReason(FinishReason.ERROR).build());
  }

  private static Flow.Publisher<StreamEvent> failingWith(Throwable failure) {
    return subscriber -> {
      subscriber.onSubscribe(
          new Flow.Subscription() {
            @Override
            public void request(long n) {
              subscriber.onError(failure);
            }

            @Override
            public void cancel() {}
          });
    };
  }

  /** The exception a test provider throws, so a pass-through is told apart from a wrap. */
  static final class AcmeException extends ProviderException {
    AcmeException(String message, int statusCode, Throwable cause) {
      super(message, statusCode, cause);
    }
  }

  /**
   * Answers each request with {@link #publisher} when set, else with {@link #events} (or the
   * reply's completion), read from a stream that blocks once its events run out when {@link
   * #blockAtEnd}.
   */
  private final class RecordingExchange implements Exchange<Request> {

    @Override
    public Flow.Publisher<StreamEvent> stream(Request request, CancellationToken cancellation) {
      chatted.add(request);
      if (publisher != null) {
        return publisher;
      }
      var turn = events != null ? events : List.<StreamEvent>of(new StreamEvent.Done(reply));
      return new IteratorPublisher<>(
          () -> {
            var stream = new ChatExchangeTest.Recorded(turn);
            stream.blocks = blockAtEnd;
            opened = stream;
            return stream;
          },
          cancellation);
    }
  }
}
