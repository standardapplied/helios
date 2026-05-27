/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai;

import ai.singlr.core.common.HttpClientFactory;
import ai.singlr.core.common.Strings;
import ai.singlr.core.model.CloseableIterator;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.StreamEvent;
import ai.singlr.core.model.ThinkingLevel;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.model.ToolChoice;
import ai.singlr.core.model.TransientStreamException;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.schema.StructuredContentParser;
import ai.singlr.core.tool.Tool;
import ai.singlr.openai.api.ApiStreamEvent;
import ai.singlr.openai.api.ContentPart;
import ai.singlr.openai.api.InputItem;
import ai.singlr.openai.api.ResponsesRequest;
import ai.singlr.openai.api.TextFormatConfig;
import ai.singlr.openai.api.ToolDefinition;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI model implementation using the Responses API.
 *
 * <p>All requests use SSE streaming internally for robust timeout handling. Synchronous {@link
 * #chat} methods stream under the hood and accumulate the response, avoiding HTTP read timeouts on
 * long-running generations. A per-line idle timeout detects stalled streams and throws a retryable
 * {@link OpenAIException}.
 */
public class OpenAIModel implements Model {

  private static final String PROVIDER_NAME = "openai";
  static final String DEFAULT_BASE_URL = "https://api.openai.com/v1/responses";

  static final String REASONING_KEY = "openai.reasoning";

  private final String wireModelId;
  private final OpenAIModelId knownModel;
  private final ModelConfig config;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  OpenAIModel(OpenAIModelId modelId, ModelConfig config) {
    this(modelId != null ? modelId.id() : null, modelId, config);
  }

  OpenAIModel(String wireModelId, ModelConfig config) {
    this(wireModelId, OpenAIModelId.fromId(wireModelId), config);
  }

  private OpenAIModel(String wireModelId, OpenAIModelId knownModel, ModelConfig config) {
    if (Strings.isBlank(wireModelId)) {
      throw new IllegalArgumentException("modelId is required");
    }
    if (config == null) {
      throw new IllegalArgumentException("config is required");
    }
    var hasCustomEndpoint = !Strings.isBlank(config.baseUrl());
    if (!hasCustomEndpoint && Strings.isBlank(config.apiKey())) {
      throw new IllegalArgumentException(
          "config with valid apiKey is required (or set baseUrl + auth header)");
    }
    this.wireModelId = wireModelId;
    this.knownModel = knownModel;
    this.config = config;
    this.httpClient = HttpClientFactory.create(config);
    this.objectMapper =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  }

  @Override
  public String id() {
    return wireModelId;
  }

  @Override
  public String provider() {
    return PROVIDER_NAME;
  }

  @Override
  public int contextWindow() {
    return knownModel != null ? knownModel.contextWindow() : 0;
  }

  @Override
  public int maxOutputTokens() {
    return knownModel != null ? knownModel.maxOutputTokens() : 0;
  }

  @Override
  public void close() {
    HttpClientFactory.shutdownGracefully(httpClient);
  }

  @Override
  public Response<Void> chat(List<Message> messages, List<Tool> tools) {
    var request = buildRequest(messages, tools, null);
    return streamAndDrain(request);
  }

  @Override
  public <T> Response<T> chat(
      List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
    var request = buildRequest(messages, tools, outputSchema.schema().toMap());
    var response = streamAndDrain(request);
    // Tool-calling turns are intermediate — structured output is the deliverable of a later
    // text-only turn. Parsing incidental prose here throws and kills the session before the loop
    // dispatches the tool.
    T parsed = null;
    if (response.toolCalls().isEmpty()) {
      parsed = parseStructuredContent(response.content(), outputSchema);
    }

    return Response.<T>newBuilder(outputSchema.type())
        .withContent(response.content())
        .withParsed(parsed)
        .withToolCalls(response.toolCalls())
        .withFinishReason(response.finishReason())
        .withUsage(response.usage())
        .withThinking(response.thinking())
        .withCitations(response.citations())
        .withMetadata(response.metadata())
        .build();
  }

  @Override
  public CloseableIterator<StreamEvent> chatStream(List<Message> messages, List<Tool> tools) {
    var request = buildRequest(messages, tools, null);
    try {
      return openStream(request);
    } catch (OpenAIException e) {
      return CloseableIterator.of(
          List.of((StreamEvent) new StreamEvent.Error(e.getMessage(), e)).iterator());
    } catch (IOException e) {
      return CloseableIterator.of(
          List.of((StreamEvent) new StreamEvent.Error("Failed to connect", e)).iterator());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return CloseableIterator.of(
          List.of((StreamEvent) new StreamEvent.Error("Request interrupted", e)).iterator());
    }
  }

  <T> T parseStructuredContent(String content, OutputSchema<T> schema) {
    return StructuredContentParser.parse(content, schema, jsonAdapter);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private final StructuredContentParser.JsonAdapter jsonAdapter =
      new StructuredContentParser.JsonAdapter() {
        @Override
        public Map<String, Object> toMap(String json) throws Exception {
          return objectMapper.readValue(json, Map.class);
        }

        @Override
        public <T> T fromMap(Map<String, Object> map, Class<T> type) {
          return objectMapper.convertValue(map, type);
        }
      };

  private StreamingIterator openStream(ResponsesRequest request)
      throws IOException, InterruptedException {
    var jsonBody = serializeRequest(request);
    var httpRequest = buildHttpRequest(jsonBody);
    var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
    if (httpResponse.statusCode() != 200) {
      try (var body = httpResponse.body()) {
        var errorBody = HttpClientFactory.readBoundedErrorBody(body);
        throw new OpenAIException(
            "API error (status " + httpResponse.statusCode() + "): " + errorBody,
            httpResponse.statusCode());
      }
    }
    return new StreamingIterator(httpResponse, objectMapper, config.streamIdleTimeout());
  }

  private Response<Void> streamAndDrain(ResponsesRequest request) {
    try (var iterator = openStream(request)) {
      return drainToResponse(iterator);
    } catch (OpenAIException | TransientStreamException e) {
      throw e;
    } catch (IOException e) {
      throw new TransientStreamException("Failed to communicate with OpenAI API", e, PROVIDER_NAME);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OpenAIException("Request interrupted", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Response<Void> drainToResponse(StreamingIterator iterator) {
    while (iterator.hasNext()) {
      var event = iterator.next();
      if (event instanceof StreamEvent.Done(var response)) {
        return (Response<Void>) response;
      }
      if (event instanceof StreamEvent.Error(String message, Exception cause)) {
        if (cause instanceof OpenAIException oe) {
          throw oe;
        }
        if (cause instanceof IOException) {
          throw new TransientStreamException(message, cause, PROVIDER_NAME);
        }
        throw new OpenAIException(message, cause);
      }
    }
    throw new OpenAIException("Stream ended without completion event");
  }

  ResponsesRequest buildRequest(
      List<Message> messages, List<Tool> tools, Map<String, Object> outputSchema) {
    var inputItems = new ArrayList<InputItem>();
    String instructions = null;

    for (var message : messages) {
      switch (message.role()) {
        case SYSTEM -> instructions = appendSystemText(instructions, message.content());
        case USER -> inputItems.add(convertUserMessage(message));
        case ASSISTANT -> inputItems.addAll(convertAssistantMessage(message));
        case TOOL ->
            inputItems.add(InputItem.functionCallOutput(message.toolCallId(), message.content()));
      }
    }

    List<ToolDefinition> toolDefs = null;
    if (tools != null && !tools.isEmpty()) {
      toolDefs =
          tools.stream()
              .map(
                  t ->
                      ToolDefinition.function(
                          t.name(), t.description(), t.parametersAsJsonSchema()))
              .toList();
    }

    var toolChoiceValue = buildToolChoice(tools);
    var reasoningConfig = buildReasoningConfig();

    Double temperature = config.temperature();
    if (reasoningConfig != null) {
      temperature = null;
    }

    var builder =
        ResponsesRequest.newBuilder()
            .withModel(wireModelId)
            .withInput(inputItems)
            .withInstructions(instructions)
            .withStream(true)
            .withTools(toolDefs)
            .withToolChoice(toolChoiceValue)
            .withTemperature(temperature)
            .withTopP(config.topP())
            .withMaxOutputTokens(
                config.maxOutputTokens() != null ? config.maxOutputTokens() : maxOutputTokens())
            .withStop(config.stopSequences())
            .withReasoning(reasoningConfig);

    if (outputSchema != null) {
      var hasOpenMap = hasOpenMapShape(outputSchema);
      var schema = hasOpenMap ? outputSchema : addAdditionalPropertiesFalse(outputSchema);
      var textFormat = TextFormatConfig.jsonSchema("output", schema, !hasOpenMap);
      builder.withText(new ResponsesRequest.TextConfig(textFormat));
    }

    return builder.build();
  }

  private static String appendSystemText(String existing, String additional) {
    if (existing == null) {
      return additional;
    }
    return existing + "\n\n" + additional;
  }

  /**
   * Returns {@code true} when the schema contains any open-keyed object — an {@code object} type
   * whose {@code additionalProperties} is a value-schema (i.e., a {@code Map<String, X>} shape)
   * rather than {@code false}.
   *
   * <p>OpenAI's strict mode rejects schemas with open-keyed objects: strict mode requires every
   * {@code object} to set {@code additionalProperties: false} and list every property in {@code
   * required}. Open Maps violate both. Detecting this lets {@link #buildRequest} fall back to
   * non-strict json_schema mode, which preserves structured output without the strict-mode
   * validator.
   */
  @SuppressWarnings("unchecked")
  static boolean hasOpenMapShape(Map<String, Object> schema) {
    if (schema == null) {
      return false;
    }
    if ("object".equals(schema.get("type"))
        && schema.get("additionalProperties") instanceof Map<?, ?>) {
      return true;
    }
    if (schema.get("properties") instanceof Map<?, ?> props) {
      for (var entry : ((Map<String, Object>) props).entrySet()) {
        if (entry.getValue() instanceof Map<?, ?> nested
            && hasOpenMapShape((Map<String, Object>) nested)) {
          return true;
        }
      }
    }
    if (schema.get("items") instanceof Map<?, ?> items
        && hasOpenMapShape((Map<String, Object>) items)) {
      return true;
    }
    if (schema.get("additionalProperties") instanceof Map<?, ?> ap
        && hasOpenMapShape((Map<String, Object>) ap)) {
      return true;
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> addAdditionalPropertiesFalse(Map<String, Object> schema) {
    var result = new HashMap<>(schema);
    if ("object".equals(result.get("type"))) {
      var existing = result.get("additionalProperties");
      if (existing instanceof Map<?, ?> existingSchema) {
        // Map value schema — recurse into it instead of overwriting
        result.put(
            "additionalProperties",
            addAdditionalPropertiesFalse((Map<String, Object>) existingSchema));
      } else {
        result.put("additionalProperties", false);
      }
      if (result.get("properties") instanceof Map<?, ?> props) {
        var newProps = new HashMap<String, Object>();
        for (var entry : ((Map<String, Object>) props).entrySet()) {
          if (entry.getValue() instanceof Map<?, ?> nested) {
            newProps.put(
                entry.getKey(), addAdditionalPropertiesFalse((Map<String, Object>) nested));
          } else {
            newProps.put(entry.getKey(), entry.getValue());
          }
        }
        result.put("properties", newProps);
      }
    }
    if ("array".equals(result.get("type")) && result.get("items") instanceof Map<?, ?> items) {
      result.put("items", addAdditionalPropertiesFalse((Map<String, Object>) items));
    }
    return result;
  }

  /**
   * Convert a Helios USER {@link Message} into a Responses-API input item. Plain text is emitted as
   * the bare-string overload (the Responses API accepts that form). When the message carries inline
   * files, the wire shape becomes a content-part array so the provider receives the image/file
   * blocks alongside the text.
   *
   * @param message the user message; non-null
   * @return the input item
   */
  static InputItem convertUserMessage(Message message) {
    var text = message.content() != null ? message.content() : "";
    if (!message.hasInlineFiles()) {
      return InputItem.userMessage(text);
    }
    var parts = new ArrayList<ContentPart>(message.inlineFiles().size() + 1);
    for (var file : message.inlineFiles()) {
      var data = Base64.getEncoder().encodeToString(file.data());
      var media = file.mimeType();
      if (media != null && media.startsWith("image/")) {
        parts.add(ContentPart.inputImage(media, data));
      } else {
        parts.add(ContentPart.inputFile(media, data, null));
      }
    }
    if (!text.isEmpty()) {
      parts.add(ContentPart.inputText(text));
    }
    return InputItem.userMessage(parts);
  }

  List<InputItem> convertAssistantMessage(Message message) {
    var items = new ArrayList<InputItem>();

    if (message.content() != null && !message.content().isEmpty()) {
      items.add(InputItem.assistantMessage(message.content()));
    }

    if (message.hasToolCalls()) {
      for (var tc : message.toolCalls()) {
        var argsJson = serializeArguments(tc.arguments());
        items.add(InputItem.functionCall(tc.id(), tc.name(), argsJson));
      }
    }

    if (items.isEmpty()) {
      items.add(InputItem.assistantMessage(""));
    }

    return items;
  }

  private String serializeArguments(Map<String, Object> arguments) {
    if (arguments == null || arguments.isEmpty()) {
      return "{}";
    }
    try {
      return objectMapper.writeValueAsString(arguments);
    } catch (Exception e) {
      throw new OpenAIException("Failed to serialize tool call arguments", e);
    }
  }

  private Object buildToolChoice(List<Tool> tools) {
    if (config.toolChoice() == null) {
      return null;
    }

    return switch (config.toolChoice()) {
      case ToolChoice.Auto a -> "auto";
      case ToolChoice.Any a -> "required";
      case ToolChoice.None n -> "none";
      case ToolChoice.Required r -> {
        var name = r.allowedTools().iterator().next();
        yield Map.of("type", "function", "name", name);
      }
    };
  }

  private ResponsesRequest.ReasoningConfig buildReasoningConfig() {
    if (config.thinkingLevel() == null || config.thinkingLevel() == ThinkingLevel.NONE) {
      return null;
    }

    // Model-aware effort dispatch. gpt-5.4 and gpt-5.5 accept the "xhigh" wire string per
    // OpenAI's published model pages; XHIGH lands there directly and MAX (no native equivalent
    // anywhere in the OpenAI surface) clamps up to xhigh on those models. Older reasoning models
    // (o3, o4-mini) and undocumented variants clamp both XHIGH and MAX to "high" — see
    // OpenAIModelId#supportsXhighEffort for the per-model matrix and the conservative-default
    // rationale.
    var topTier = knownModel != null && knownModel.supportsXhighEffort() ? "xhigh" : "high";
    var effort =
        switch (config.thinkingLevel()) {
          case NONE -> null;
          case MINIMAL, LOW -> "low";
          case MEDIUM -> "medium";
          case HIGH -> "high";
          case XHIGH, MAX -> topTier;
        };

    return ResponsesRequest.ReasoningConfig.of(effort);
  }

  String serializeRequest(ResponsesRequest request) {
    try {
      return objectMapper.writeValueAsString(request);
    } catch (Exception e) {
      throw new OpenAIException("Failed to serialize request", e);
    }
  }

  HttpRequest buildHttpRequest(String jsonBody) {
    var defaults = new LinkedHashMap<String, String>();
    defaults.put("Content-Type", "application/json");
    if (!Strings.isBlank(config.apiKey())) {
      defaults.put("Authorization", "Bearer " + config.apiKey());
    }
    var builder =
        HttpRequest.newBuilder()
            .uri(URI.create(config.effectiveBaseUrl(DEFAULT_BASE_URL)))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
    for (var entry : config.effectiveHeaders(defaults).entrySet()) {
      builder.header(entry.getKey(), entry.getValue());
    }
    // Null-guard matches Anthropic/Gemini parity — HttpRequest.Builder.timeout(null) NPEs and
    // ModelConfig.Builder.withResponseTimeout(null) is currently legal.
    if (config.responseTimeout() != null) {
      builder.timeout(config.responseTimeout());
    }
    return builder.build();
  }

  static FinishReason mapStatus(String status) {
    if (status == null) {
      return FinishReason.STOP;
    }
    return switch (status) {
      case "completed" -> FinishReason.STOP;
      case "incomplete" -> FinishReason.LENGTH;
      case "failed" -> FinishReason.ERROR;
      default -> FinishReason.STOP;
    };
  }

  static class StreamingIterator implements CloseableIterator<StreamEvent> {
    private final InputStream rawStream;
    private final BufferedReader reader;
    private final ObjectMapper objectMapper;
    private final Duration streamIdleTimeout;
    private final ExecutorService readExecutor;
    private final StringBuilder contentBuilder = new StringBuilder();
    private final List<ToolCall> toolCalls = new ArrayList<>();
    private final Map<String, ToolCallAccumulator> toolCallAccumulators = new HashMap<>();
    private final StringBuilder reasoningBuilder = new StringBuilder();
    private StreamEvent nextEvent = null;
    private boolean done = false;
    private int inputTokens = 0;
    private int outputTokens = 0;
    private int cachedInputTokens = 0;
    private String responseStatus = null;

    StreamingIterator(
        HttpResponse<InputStream> response, ObjectMapper objectMapper, Duration streamIdleTimeout) {
      this.rawStream = response.body();
      this.reader =
          new BufferedReader(new InputStreamReader(this.rawStream, StandardCharsets.UTF_8));
      this.objectMapper = objectMapper;
      this.streamIdleTimeout = streamIdleTimeout;
      this.readExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public boolean hasNext() {
      if (done) {
        return false;
      }
      if (nextEvent != null) {
        return true;
      }
      nextEvent = readNextEvent();
      return nextEvent != null;
    }

    @Override
    public StreamEvent next() {
      if (nextEvent == null) {
        nextEvent = readNextEvent();
      }
      var event = nextEvent;
      nextEvent = null;
      return event;
    }

    private String readLineWithTimeout() throws IOException {
      Future<String> future = readExecutor.submit((Callable<String>) () -> reader.readLine());
      try {
        return future.get(streamIdleTimeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        future.cancel(true);
        throw new OpenAIException(
            "Stream idle timeout: no data received for " + streamIdleTimeout.toSeconds() + "s");
      } catch (ExecutionException e) {
        if (e.getCause() instanceof IOException ioe) {
          throw ioe;
        }
        throw new IOException("Stream read failed", e.getCause());
      } catch (InterruptedException e) {
        future.cancel(true);
        Thread.currentThread().interrupt();
        throw new IOException("Stream read interrupted", e);
      }
    }

    private StreamEvent readNextEvent() {
      try {
        String line;
        while ((line = readLineWithTimeout()) != null) {
          if (line.startsWith("data: ")) {
            var json = line.substring(6).trim();
            if (json.isEmpty() || json.equals("[DONE]")) {
              continue;
            }
            var event = parseStreamEvent(json);
            if (event != null) {
              return event;
            }
          }
        }
        done = true;
        close();
        return buildDoneEvent();
      } catch (OpenAIException e) {
        done = true;
        close();
        return new StreamEvent.Error(e.getMessage(), e);
      } catch (IOException e) {
        done = true;
        close();
        return new StreamEvent.Error("Stream read error", e);
      }
    }

    @SuppressWarnings("unchecked")
    private StreamEvent parseStreamEvent(String json) {
      try {
        var event = objectMapper.readValue(json, ApiStreamEvent.class);

        if (event.hasTypeResponseOutputTextDelta()) {
          if (event.delta() != null) {
            contentBuilder.append(event.delta());
            return new StreamEvent.TextDelta(event.delta());
          }
          return null;
        }

        if (event.hasTypeResponseOutputItemAdded()) {
          if (event.item() != null && event.item().hasTypeFunctionCall()) {
            toolCallAccumulators.put(
                event.item().id(),
                new ToolCallAccumulator(
                    event.item().callId(), event.item().name(), new StringBuilder()));
            return new StreamEvent.ToolCallStart(event.item().callId(), event.item().name());
          }
          return null;
        }

        if (event.hasTypeFunctionCallArgumentsDelta()) {
          if (event.delta() != null && event.itemId() != null) {
            var accumulator = toolCallAccumulators.get(event.itemId());
            if (accumulator != null) {
              accumulator.jsonBuilder().append(event.delta());
            }
          }
          return null;
        }

        if (event.hasTypeFunctionCallArgumentsDone()) {
          if (event.itemId() != null) {
            var accumulator = toolCallAccumulators.remove(event.itemId());
            if (accumulator != null) {
              var jsonStr = accumulator.jsonBuilder().toString();
              Map<String, Object> arguments = Map.of();
              if (!jsonStr.isEmpty()) {
                try {
                  arguments = objectMapper.readValue(jsonStr, Map.class);
                } catch (Exception e) {
                  arguments = Map.of("_raw", jsonStr);
                }
              }
              var tc =
                  ToolCall.newBuilder()
                      .withId(accumulator.callId())
                      .withName(accumulator.name())
                      .withArguments(arguments)
                      .build();
              toolCalls.add(tc);
              return new StreamEvent.ToolCallComplete(tc);
            }
          }
          return null;
        }

        if (event.hasTypeResponseCompleted()) {
          if (event.response() != null) {
            responseStatus = event.response().status();
            if (event.response().usage() != null) {
              var usage = event.response().usage();
              if (usage.inputTokens() != null) {
                inputTokens = usage.inputTokens();
              }
              if (usage.outputTokens() != null) {
                outputTokens = usage.outputTokens();
              }
              cachedInputTokens = usage.cachedTokensOrZero();
            }
          }
          done = true;
          close();
          return buildDoneEvent();
        }

        if (event.hasTypeResponseFailed()) {
          done = true;
          close();
          return new StreamEvent.Error("API response failed: " + json, null);
        }

        if (event.hasTypeError()) {
          return new StreamEvent.Error("API stream error: " + json, null);
        }

        if (event.hasTypeReasoningSummaryTextDelta()) {
          if (event.text() != null) {
            reasoningBuilder.append(event.text());
            return new StreamEvent.ThinkingDelta(event.text());
          }
          return null;
        }

        // Reasoning summary block closing — emit terminal aggregation so consumers can stop
        // accumulating deltas and capture the full reasoning text. OpenAI's Responses API does
        // not surface a signature for reasoning summaries, so the second arg is null.
        if (event.hasTypeReasoningSummaryTextDone() && !reasoningBuilder.isEmpty()) {
          return new StreamEvent.ThinkingComplete(reasoningBuilder.toString(), null);
        }

        return null;
      } catch (Exception e) {
        return new StreamEvent.Error("Failed to parse stream event", e);
      }
    }

    private StreamEvent buildDoneEvent() {
      var content = contentBuilder.toString();
      var calls = toolCalls.isEmpty() ? List.<ToolCall>of() : List.copyOf(toolCalls);

      var finishReason = mapStatus(responseStatus);
      if (!calls.isEmpty() && finishReason != FinishReason.TOOL_CALLS) {
        finishReason = FinishReason.TOOL_CALLS;
      }

      Response.Usage usage = null;
      if (inputTokens > 0 || outputTokens > 0 || cachedInputTokens > 0) {
        // OpenAI's wire shape reports input_tokens as TOTAL (cached + uncached) and
        // input_tokens_details.cached_tokens as a SUBSET. The Helios canonical shape is disjoint
        // — every token in exactly one class — so we subtract here. Bounded by zero in case the
        // server ever reports a cached subset > total (would indicate a server-side accounting
        // bug; we'd rather under-report uncached than synthesize a negative count).
        var uncachedInput = Math.max(0, inputTokens - cachedInputTokens);
        // OpenAI does not premium cache writes, so cacheCreationInputTokens stays zero.
        usage = Response.Usage.of(uncachedInput, outputTokens, 0, cachedInputTokens);
      }

      String thinking = reasoningBuilder.isEmpty() ? null : reasoningBuilder.toString();

      var metadata = new HashMap<String, String>();
      if (thinking != null) {
        metadata.put(REASONING_KEY, thinking);
      }

      var response =
          Response.newBuilder()
              .withContent(content)
              .withToolCalls(calls)
              .withFinishReason(finishReason)
              .withUsage(usage)
              .withThinking(thinking)
              .withMetadata(metadata.isEmpty() ? Map.of() : Map.copyOf(metadata))
              .build();

      return new StreamEvent.Done(response);
    }

    @Override
    public void close() {
      done = true;
      readExecutor.shutdownNow();
      try {
        rawStream.close();
      } catch (IOException ignored) {
      }
      try {
        reader.close();
      } catch (IOException ignored) {
      }
    }

    private record ToolCallAccumulator(String callId, String name, StringBuilder jsonBuilder) {}
  }
}
