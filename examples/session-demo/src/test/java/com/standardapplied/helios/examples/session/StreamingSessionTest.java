/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.anthropic.AnthropicException;
import com.standardapplied.helios.anthropic.AnthropicModelId;
import com.standardapplied.helios.anthropic.AnthropicProvider;
import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.HeldSseServer;
import com.standardapplied.helios.core.test.ModelHarness;
import com.standardapplied.helios.core.test.SseReplies;
import com.standardapplied.helios.core.test.StubHttpServer;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.gemini.GeminiException;
import com.standardapplied.helios.gemini.GeminiModelId;
import com.standardapplied.helios.gemini.GeminiProvider;
import com.standardapplied.helios.openai.OpenAIException;
import com.standardapplied.helios.openai.OpenAIModelId;
import com.standardapplied.helios.openai.OpenAIProvider;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.StreamRetryPolicy;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A session streams each provider's answer through the real provider client as it arrives, and
 * cancelling a session releases its request and connection alone. A localhost fixture holds each
 * reply open until the test releases it, and reports when the client closes the connection.
 *
 * <p>{@code <provider>/sessions/held-text.sse} (anthropic, gemini, openai) is hand-built from the
 * provider's documented stream shape, the text turn of its {@code streams/text.sse} golden, split
 * at {@code === hold ===} where the fixture holds the reply.
 *
 * <p>{@code anthropic/sessions/structured-tool.sse} is hand-built from the documented Messages
 * stream shape: a thinking block and a tool call, then a thinking block and the JSON answer.
 *
 * <p>{@code gemini/sessions/cited.sse} is a copy of the {@code gemini/streams/citations.sse}
 * golden: a text answer whose annotations become the turn's citations.
 *
 * <p>{@code <provider>/sessions/held-tool.sse} is the first tool call of the provider's {@code
 * streams/tool-calls.sse} golden, held after its first argument fragment: argument fragments, like
 * every frame, are lines that keep the model's {@code streamIdleTimeout} from firing while a long
 * call streams.
 *
 * <p>The idle-clock tests hold a {@code held-text.sse} reply at its headers, as Gemini does while
 * it thinks, or after its first part.
 */
final class StreamingSessionTest {

  static final String HOLD = "=== hold ===\n";

  private static final Map<String, Class<? extends RuntimeException>> PROVIDER_EXCEPTIONS =
      Map.of(
          "anthropic", AnthropicException.class,
          "gemini", GeminiException.class,
          "openai", OpenAIException.class);

  @ParameterizedTest
  @ValueSource(strings = {"anthropic", "gemini", "openai"})
  void theFirstDeltaReachesTheSessionBeforeTheReplyIsReleased(String provider) throws IOException {
    var reply = held(provider);
    try (var server = HeldSseServer.start(reply);
        var model = modelAt(provider, server.uri());
        var session = AgentSession.create(SessionOptions.newBuilder().withModel(model).build())) {
      var texts = assistantTexts(session);
      session.send(UserMessage.text("Hi"));

      var first = Await.next("the first text delta", texts);
      reply.release();

      assertEquals("Hello", first.text());
      var success = assertInstanceOf(ResultMessage.Success.class, result(session));
      assertEquals("Hello, world.", success.result());
      assertEquals(1, server.requests());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"anthropic", "gemini", "openai"})
  void toolArgumentsStreamBeforeTheirCallCompletes(String provider) throws IOException {
    var reply =
        HeldSseServer.Reply.of(Golden.read(provider + "/sessions/held-tool.sse").split(HOLD));
    var chunks = new LinkedBlockingQueue<ModelChunk>();
    try (var server = HeldSseServer.start(reply);
        var model = modelAt(provider, server.uri())) {
      var ended =
          readInto(
              chunks,
              model.chatStream(List.of(Message.user("Weather?")), List.of(weather().tool())));

      var start = assertInstanceOf(ModelChunk.ToolUseStart.class, nextContent(chunks));
      var delta = nextContent(chunks);
      reply.release();

      assertEquals(new ModelChunk.ToolUseDelta(start.callId(), "{\"city\""), delta);
      assertEquals(new ModelChunk.ToolUseDelta(start.callId(), ":\"Paris\"}"), nextContent(chunks));
      assertInstanceOf(ModelChunk.ToolUseStop.class, nextContent(chunks));
      Await.value("the end of the stream", ended);
      assertInstanceOf(ModelChunk.MessageStop.class, List.copyOf(chunks).getLast());
    }
  }

  @Test
  void aModelHoldingBackItsHeadersIsEndedByTheRetryableResponseTimeoutNotTheIdleClock()
      throws IOException {
    var reply =
        HeldSseServer.Reply.holdingHeaders(
            Golden.read("gemini/sessions/held-text.sse").split(HOLD));
    var limits =
        SessionLimits.newBuilder().withStreamRetryPolicy(StreamRetryPolicy.disabled()).build();
    try (var server = HeldSseServer.start(reply);
        var model =
            modelAt(
                "gemini",
                server.uri(),
                ModelConfig.newBuilder()
                    .withResponseTimeout(Duration.ofMillis(200))
                    .withStreamIdleTimeout(Duration.ofMillis(1)));
        var session =
            AgentSession.create(
                SessionOptions.newBuilder().withModel(model).withLimits(limits).build())) {

      session.send(UserMessage.text("Hi"));

      assertInstanceOf(ResultMessage.ErrorTransientStream.class, result(session));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"anthropic", "gemini", "openai"})
  void aStreamSilentAfterItsFirstPartIsEndedByTheModelsIdleClockWhichClosesTheConnection(
      String provider) throws IOException {
    var reply = held(provider);
    try (var server = HeldSseServer.start(reply);
        var model =
            modelAt(
                provider,
                server.uri(),
                ModelConfig.newBuilder().withStreamIdleTimeout(Duration.ofMillis(300)));
        var session = AgentSession.create(SessionOptions.newBuilder().withModel(model).build())) {
      session.send(UserMessage.text("Hi"));

      reply.awaitClientClosed();

      var failed = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result(session));
      assertEquals(PROVIDER_EXCEPTIONS.get(provider).getName(), failed.error().kind());
      assertTrue(
          failed.error().message().contains("Stream idle timeout"), failed.error().message());
    }
  }

  @Test
  void thinkingToolCallsAndAStructuredAnswerTraverseTheStreamingPath() throws IOException {
    record Forecast(String city, String summary) {}
    var replies =
        Arrays.asList(
            Golden.read("anthropic/sessions/structured-tool.sse")
                .split(ModelHarness.NEXT_RESPONSE));
    var events = new EventCollector();
    try (var server =
            StubHttpServer.start(InetAddress.getLoopbackAddress(), 0, SseReplies.inOrder(replies));
        var model = modelAt("anthropic", server.uri());
        var session =
            AgentSession.create(
                SessionOptions.newBuilder()
                    .withModel(model)
                    .withTools(List.of(weather()))
                    .withOutputSchema(OutputSchema.of(Forecast.class))
                    .build())) {
      events.read(session.events());

      var forecast =
          session.runBlocking(UserMessage.text("Weather?"), OutputSchema.of(Forecast.class));
      events.awaitDone();

      assertEquals(new Forecast("Paris", "Sunny"), forecast);
      assertEquals(
          List.of("I need the weather first.", "Sunny, so I answer."),
          events.eventsOf(QueryEvent.AssistantThinking.class).stream()
              .map(QueryEvent.AssistantThinking::text)
              .toList());
      assertEquals(
          List.of("{\"city\":\"Paris\",", "\"summary\":\"Sunny\"}"),
          events.eventsOf(QueryEvent.AssistantText.class).stream()
              .map(QueryEvent.AssistantText::text)
              .toList());
      assertEquals(
          "sunny", events.eventsOf(QueryEvent.ToolResult.class).getFirst().result().output());
    }
  }

  @Test
  void citationsTraverseTheStreamingPath() throws IOException {
    var events = new EventCollector();
    try (var server =
            StubHttpServer.start(
                InetAddress.getLoopbackAddress(),
                0,
                SseReplies.inOrder(List.of(Golden.read("gemini/sessions/cited.sse"))));
        var model = modelAt("gemini", server.uri());
        var session = AgentSession.create(SessionOptions.newBuilder().withModel(model).build())) {
      events.read(session.events());

      session.send(UserMessage.text("Paris?"));
      var success = assertInstanceOf(ResultMessage.Success.class, result(session));
      events.awaitDone();

      var cited = events.eventsOf(QueryEvent.AssistantCitations.class).getFirst().citations();
      assertEquals(
          List.of("https://a.example", "https://b.example", "https://c.example"),
          cited.stream().map(Citation::sourceId).toList());
      assertEquals(cited, success.citations());
    }
  }

  @Test
  void aSessionCancelledBeforeItsModelCallSendsNoRequest() throws IOException {
    var reply = held("anthropic");
    PreModelTurnHook cancelFirst =
        (history, context) -> {
          context.cancellation().cancel("cancelled before the model call");
          return HookOutcome.cont();
        };
    try (var server = HeldSseServer.start(reply);
        var model = modelAt("anthropic", server.uri());
        var session =
            AgentSession.create(
                SessionOptions.newBuilder().withModel(model).withHook(cancelFirst).build())) {
      session.send(UserMessage.text("Hi"));

      assertInstanceOf(ResultMessage.Cancelled.class, result(session));
      assertEquals(0, server.requests());
    }
  }

  @Test
  void closingASessionAwaitingHeadersClosesItsConnection() throws IOException {
    var parts = Golden.read("anthropic/sessions/held-text.sse").split(HOLD);
    var reply = HeldSseServer.Reply.holdingHeaders(parts);
    try (var server = HeldSseServer.start(reply);
        var model = modelAt("anthropic", server.uri())) {
      var session = AgentSession.create(SessionOptions.newBuilder().withModel(model).build());
      session.send(UserMessage.text("Hi"));
      reply.awaitRequest();

      session.close();

      reply.awaitClientClosed();
      assertInstanceOf(ResultMessage.Cancelled.class, result(session));
    }
  }

  @Test
  void closingASessionMidStreamClosesItsConnection() throws IOException {
    var reply = held("anthropic");
    try (var server = HeldSseServer.start(reply);
        var model = modelAt("anthropic", server.uri())) {
      var session = AgentSession.create(SessionOptions.newBuilder().withModel(model).build());
      var texts = assistantTexts(session);
      session.send(UserMessage.text("Hi"));
      Await.next("the first text delta", texts);

      session.close();

      reply.awaitClientClosed();
      assertInstanceOf(ResultMessage.Cancelled.class, result(session));
    }
  }

  @Test
  void closingASessionAfterItsResultLeavesTheResult() throws IOException {
    var reply = held("anthropic");
    reply.release();
    try (var server = HeldSseServer.start(reply);
        var model = modelAt("anthropic", server.uri())) {
      var session = AgentSession.create(SessionOptions.newBuilder().withModel(model).build());
      session.send(UserMessage.text("Hi"));
      var settled = result(session);
      reply.awaitFinished();

      session.close();

      assertInstanceOf(ResultMessage.Success.class, settled);
      assertEquals(settled, result(session));
    }
  }

  @Test
  void oneSessionCancellingASharedModelLeavesAnotherStreaming() throws IOException {
    var cancelled = held("anthropic");
    var other = held("anthropic");
    var after = held("anthropic");
    other.release();
    after.release();
    try (var server = HeldSseServer.start(cancelled, other, after);
        var model = modelAt("anthropic", server.uri());
        var survivor = AgentSession.create(SessionOptions.newBuilder().withModel(model).build());
        var later = AgentSession.create(SessionOptions.newBuilder().withModel(model).build())) {
      var victim = AgentSession.create(SessionOptions.newBuilder().withModel(model).build());
      var texts = assistantTexts(victim);
      victim.send(UserMessage.text("Hi"));
      Await.next("the first text delta", texts);
      survivor.send(UserMessage.text("Hi"));
      other.awaitRequest();

      victim.close();
      cancelled.awaitClientClosed();
      later.send(UserMessage.text("Hi"));

      assertInstanceOf(ResultMessage.Cancelled.class, result(victim));
      assertEquals(
          "Hello, world.",
          assertInstanceOf(ResultMessage.Success.class, result(survivor)).result());
      assertEquals(
          "Hello, world.", assertInstanceOf(ResultMessage.Success.class, result(later)).result());
    }
  }

  private static HeldSseServer.Reply held(String provider) {
    return HeldSseServer.Reply.of(Golden.read(provider + "/sessions/held-text.sse").split(HOLD));
  }

  private static LinkedBlockingQueue<QueryEvent.AssistantText> assistantTexts(
      AgentSession session) {
    return queued(session, QueryEvent.AssistantText.class);
  }

  static <E extends QueryEvent> LinkedBlockingQueue<E> queued(AgentSession session, Class<E> type) {
    var queue = new LinkedBlockingQueue<E>();
    new EventCollector(
            event -> {
              if (type.isInstance(event)) {
                queue.add(type.cast(event));
              }
            })
        .read(session.events());
    return queue;
  }

  static ResultMessage result(AgentSession session) {
    return Await.value("the session's result", session.result());
  }

  private static CompletableFuture<Void> readInto(
      LinkedBlockingQueue<ModelChunk> chunks, CloseableIterator<ModelChunk> stream) {
    var ended = new CompletableFuture<Void>();
    Thread.ofVirtual()
        .start(
            () -> {
              try (stream) {
                while (stream.hasNext()) {
                  chunks.add(stream.next());
                }
                ended.complete(null);
              } catch (RuntimeException e) {
                ended.completeExceptionally(e);
              }
            });
    return ended;
  }

  private static ModelChunk nextContent(LinkedBlockingQueue<ModelChunk> chunks) {
    var chunk = Await.next("the next chunk", chunks);
    while (chunk instanceof ModelChunk.KeepAlive) {
      chunk = Await.next("the next chunk", chunks);
    }
    return chunk;
  }

  static Model modelAt(String provider, URI uri) {
    return modelAt(provider, uri, ModelConfig.newBuilder());
  }

  private static Model modelAt(String provider, URI uri, ModelConfig.Builder settings) {
    Function<String, ModelConfig> config =
        baseUrl -> settings.withApiKey("test-key").withBaseUrl(baseUrl).build();
    return switch (provider) {
      case "anthropic" ->
          new AnthropicProvider()
              .create(AnthropicModelId.CLAUDE_OPUS_5_5.id(), config.apply(uri + "/v1/messages"));
      case "gemini" ->
          new GeminiProvider()
              .create(GeminiModelId.GEMINI_3_5_FLASH.id(), config.apply(uri + "/v1beta"));
      default ->
          new OpenAIProvider()
              .create(OpenAIModelId.GPT_5_6.id(), config.apply(uri + "/v1/responses"));
    };
  }

  private static ToolBinding weather() {
    var tool =
        Tool.newBuilder()
            .withName("weather")
            .withDescription("The weather in a city")
            .withIdempotent(true)
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("city")
                    .withType(ParameterType.STRING)
                    .withDescription("city")
                    .withRequired(true)
                    .build())
            .withExecutor((arguments, context) -> ToolResult.success("sunny"))
            .build();
    return ToolBinding.newBuilder(tool).withCategory(ToolCategory.READ).build();
  }
}
