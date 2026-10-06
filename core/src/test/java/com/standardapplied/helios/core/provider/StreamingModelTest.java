/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.schema.StructuredContentParser;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.tool.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

  private final List<Request> chatted = new ArrayList<>();
  private final List<Request> streamed = new ArrayList<>();
  private Response<Void> reply = done("{\"summary\":\"ok\"}");

  @Test
  void chatRunsTheBuiltRequestThroughTheExchange() {
    var response = model(config()).chat(MESSAGES, List.of());

    assertSame(reply, response);
    assertEquals(List.of(new Request(MESSAGES, List.of(), null)), chatted);
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
  void chatStreamStreamsTheBuiltRequestThroughTheExchange() {
    var events = model(config()).chatStream(MESSAGES, List.of());

    assertEquals(new StreamEvent.Done(reply), events.next());
    assertEquals(List.of(new Request(MESSAGES, List.of(), null)), streamed);
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

  private final class RecordingExchange implements Exchange<Request> {

    @Override
    public Response<Void> chat(Request request) {
      chatted.add(request);
      return reply;
    }

    @Override
    public CloseableIterator<StreamEvent> stream(Request request) {
      streamed.add(request);
      return CloseableIterator.of(List.<StreamEvent>of(new StreamEvent.Done(reply)).iterator());
    }
  }
}
