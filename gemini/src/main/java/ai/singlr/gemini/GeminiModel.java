/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import ai.singlr.core.common.HttpClientFactory;
import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Citation;
import ai.singlr.core.model.CloseableIterator;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Role;
import ai.singlr.core.model.StreamEvent;
import ai.singlr.core.model.ThinkingLevel;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.model.ToolChoice;
import ai.singlr.core.model.TransientStreamException;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.schema.StructuredContentParser;
import ai.singlr.core.tool.Tool;
import ai.singlr.gemini.api.ContentItem;
import ai.singlr.gemini.api.InteractionGenerationConfig;
import ai.singlr.gemini.api.InteractionRequest;
import ai.singlr.gemini.api.InteractionResponse;
import ai.singlr.gemini.api.InteractionUsage;
import ai.singlr.gemini.api.ResponseFormat;
import ai.singlr.gemini.api.Step;
import ai.singlr.gemini.api.StreamingEvent;
import ai.singlr.gemini.api.ToolChoiceConfig;
import ai.singlr.gemini.api.ToolDefinition;
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
 * Gemini model implementation using the Interactions API ({@code Api-Revision: 2026-05-20}).
 *
 * <p>All requests use SSE streaming internally for robust timeout handling. Synchronous {@link
 * #chat} methods stream under the hood and accumulate the response, avoiding HTTP read timeouts on
 * long-running generations. A per-line idle timeout detects stalled streams and throws a retryable
 * {@link GeminiException}.
 */
public class GeminiModel implements Model {

  private static final String PROVIDER_NAME = "gemini";
  static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
  static final String API_REVISION = "2026-05-20";
  static final String THOUGHT_SIGNATURES_KEY = "gemini.thoughtSignatures";
  static final String INTERACTION_ID_KEY = "gemini.interactionId";
  static final String SIGNATURE_DELIMITER = "\u001E";

  private final GeminiModelId modelId;
  private final ModelConfig config;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  GeminiModel(GeminiModelId modelId, ModelConfig config) {
    if (modelId == null) {
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
    this.modelId = modelId;
    this.config = config;
    this.httpClient = HttpClientFactory.create(config);
    this.objectMapper =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  }

  @Override
  public String id() {
    return modelId.id();
  }

  @Override
  public String provider() {
    return PROVIDER_NAME;
  }

  @Override
  public int contextWindow() {
    return modelId.contextWindow();
  }

  @Override
  public int maxOutputTokens() {
    return modelId.maxOutputTokens();
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
    var request = buildRequest(messages, tools, ResponseFormat.json(outputSchema.schema().toMap()));
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
    } catch (GeminiException e) {
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
        public java.util.Map<String, Object> toMap(String json) throws Exception {
          return objectMapper.readValue(json, java.util.Map.class);
        }

        @Override
        public <T> T fromMap(java.util.Map<String, Object> map, Class<T> type) {
          return objectMapper.convertValue(map, type);
        }
      };

  private StreamingIterator openStream(InteractionRequest request)
      throws IOException, InterruptedException {
    var jsonBody = serializeRequest(request);
    var httpRequest = buildHttpRequest(jsonBody);
    var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
    if (httpResponse.statusCode() != 200) {
      try (var body = httpResponse.body()) {
        var errorBody = HttpClientFactory.readBoundedErrorBody(body);
        throw new GeminiException(
            "API error (status " + httpResponse.statusCode() + "): " + errorBody,
            httpResponse.statusCode());
      }
    }
    return new StreamingIterator(httpResponse, objectMapper, config.streamIdleTimeout());
  }

  private Response<Void> streamAndDrain(InteractionRequest request) {
    try (var iterator = openStream(request)) {
      return drainToResponse(iterator);
    } catch (GeminiException | TransientStreamException e) {
      throw e;
    } catch (IOException e) {
      throw new TransientStreamException("Failed to communicate with Gemini API", e, PROVIDER_NAME);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GeminiException("Request interrupted", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Response<Void> drainToResponse(StreamingIterator iterator) {
    while (iterator.hasNext()) {
      var event = iterator.next();
      if (event instanceof StreamEvent.Done(var response)) {
        return (Response<Void>) response;
      }
      if (event instanceof StreamEvent.Error(String message, Exception cause)) {
        if (cause instanceof GeminiException ge) {
          throw ge;
        }
        if (cause instanceof IOException) {
          throw new TransientStreamException(message, cause, PROVIDER_NAME);
        }
        throw new GeminiException(message, cause);
      }
    }
    throw new GeminiException("Stream ended without completion event");
  }

  InteractionRequest buildRequest(
      List<Message> messages, List<Tool> tools, ResponseFormat responseFormat) {

    List<ToolDefinition> toolDefinitions = null;
    if (tools != null && !tools.isEmpty()) {
      toolDefinitions =
          new ArrayList<>(
              tools.stream()
                  .map(
                      t ->
                          ToolDefinition.function(
                              t.name(), t.description(), t.parametersAsJsonSchema()))
                  .toList());
    }
    if (config.urlContext() && tools != null && !tools.isEmpty()) {
      throw new IllegalStateException("URL context cannot be combined with function calling");
    }
    if (config.googleSearch()) {
      if (toolDefinitions == null) {
        toolDefinitions = new ArrayList<>();
      }
      toolDefinitions.add(ToolDefinition.googleSearch());
    }
    if (config.urlContext()) {
      if (toolDefinitions == null) {
        toolDefinitions = new ArrayList<>();
      }
      toolDefinitions.add(ToolDefinition.urlContext());
    }

    var generationConfig = buildGenerationConfig();

    var continuation = findContinuationPoint(messages);
    if (continuation != null) {
      var systemInstruction = extractSystemInstruction(messages);
      var continuationSteps = buildContinuationSteps(messages, continuation.startIndex);
      return InteractionRequest.newBuilder()
          .withModel(modelId.id())
          .withInput(continuationSteps)
          .withPreviousInteractionId(continuation.interactionId)
          .withSystemInstruction(systemInstruction)
          .withTools(toolDefinitions)
          .withGenerationConfig(generationConfig)
          .withResponseFormat(responseFormat)
          .withStream(true)
          .build();
    }

    var converted = convertMessages(messages);
    return InteractionRequest.newBuilder()
        .withModel(modelId.id())
        .withInput(converted.steps)
        .withSystemInstruction(converted.systemInstruction)
        .withTools(toolDefinitions)
        .withGenerationConfig(generationConfig)
        .withResponseFormat(responseFormat)
        .withStream(true)
        .build();
  }

  record ContinuationPoint(String interactionId, int startIndex) {}

  static ContinuationPoint findContinuationPoint(List<Message> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      var msg = messages.get(i);
      if (msg.role() == Role.ASSISTANT && msg.metadata() != null) {
        var interactionId = msg.metadata().get(INTERACTION_ID_KEY);
        if (interactionId != null && !interactionId.isEmpty()) {
          return new ContinuationPoint(interactionId, i + 1);
        }
      }
    }
    return null;
  }

  static List<Step> buildContinuationSteps(List<Message> messages, int startIndex) {
    var steps = new ArrayList<Step>();
    for (int i = startIndex; i < messages.size(); i++) {
      var msg = messages.get(i);
      switch (msg.role()) {
        case TOOL ->
            steps.add(Step.functionResult(msg.toolCallId(), msg.toolName(), msg.content()));
        case USER -> steps.add(buildUserStep(msg));
        default -> {
          // ASSISTANT / SYSTEM are not re-emitted in continuation mode (server replays them from
          // previous_interaction_id), and roles beyond USER/TOOL aren't introduced
          // post-continuation.
        }
      }
    }
    return steps;
  }

  static String interactionsContentType(String mimeType) {
    if (mimeType.startsWith("image/")) return "image";
    if (mimeType.startsWith("audio/")) return "audio";
    if (mimeType.startsWith("video/")) return "video";
    return "document";
  }

  static String extractSystemInstruction(List<Message> messages) {
    String instruction = null;
    for (var message : messages) {
      if (message.role() == Role.SYSTEM) {
        instruction = message.content();
      }
    }
    return instruction;
  }

  record ConvertedMessages(List<Step> steps, String systemInstruction) {}

  ConvertedMessages convertMessages(List<Message> messages) {
    var steps = new ArrayList<Step>();
    String systemInstruction = null;

    for (var message : messages) {
      switch (message.role()) {
        case SYSTEM -> systemInstruction = message.content();
        case USER -> steps.add(buildUserStep(message));
        case ASSISTANT -> appendAssistantSteps(message, steps);
        case TOOL ->
            steps.add(
                Step.functionResult(message.toolCallId(), message.toolName(), message.content()));
      }
    }

    return new ConvertedMessages(steps, systemInstruction);
  }

  private static Step buildUserStep(Message message) {
    if (message.hasInlineFiles()) {
      var items = new ArrayList<ContentItem>();
      for (var file : message.inlineFiles()) {
        var contentType = interactionsContentType(file.mimeType());
        var base64 = Base64.getEncoder().encodeToString(file.data());
        items.add(ContentItem.inlineData(contentType, file.mimeType(), base64));
      }
      if (message.content() != null) {
        items.add(ContentItem.text(message.content()));
      }
      return Step.userInput(items);
    }
    return Step.userInput(message.content());
  }

  private static void appendAssistantSteps(Message message, List<Step> steps) {
    if (message.hasToolCalls()) {
      var signatures = message.metadata().getOrDefault(THOUGHT_SIGNATURES_KEY, "");
      if (!signatures.isEmpty()) {
        for (var sig : signatures.split(SIGNATURE_DELIMITER)) {
          steps.add(Step.thought(sig));
        }
      }
      for (var tc : message.toolCalls()) {
        steps.add(Step.functionCall(tc.id(), tc.name(), tc.arguments()));
      }
      return;
    }
    steps.add(Step.modelOutput(message.content()));
  }

  private InteractionGenerationConfig buildGenerationConfig() {
    // Always send max_output_tokens. The model-specific default lives on GeminiModelId so callers
    // that omit ModelConfig.withMaxOutputTokens(...) aren't silently capped at the API's own
    // default (which can truncate long structured outputs without surfacing why). Other generation
    // params remain opt-in.
    var builder = InteractionGenerationConfig.newBuilder();
    builder.withMaxOutputTokens(
        config.maxOutputTokens() != null ? config.maxOutputTokens() : modelId.maxOutputTokens());

    if (config.temperature() != null) {
      builder.withTemperature(config.temperature());
    }
    if (config.topP() != null) {
      builder.withTopP(config.topP());
    }
    if (config.stopSequences() != null) {
      builder.withStopSequences(config.stopSequences());
    }
    if (config.seed() != null) {
      builder.withSeed(config.seed());
    }
    if (config.thinkingLevel() != null && config.thinkingLevel() != ThinkingLevel.NONE) {
      // Gemini exposes minimal / low / medium / high — XHIGH and MAX are Anthropic-only effort
      // tiers that clamp to high here. Callers who target XHIGH/MAX on a Gemini model get
      // Gemini's highest reasoning tier rather than a request error.
      var thinkingLevel =
          switch (config.thinkingLevel()) {
            case NONE -> "none";
            case MINIMAL -> "minimal";
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH, XHIGH, MAX -> "high";
          };
      builder.withThinkingLevel(thinkingLevel);
    }
    var toolChoice = buildToolChoice();
    if (toolChoice != null) {
      builder.withToolChoice(toolChoice);
    }

    return builder.build();
  }

  private ToolChoiceConfig buildToolChoice() {
    if (config.toolChoice() == null) {
      return null;
    }

    return switch (config.toolChoice()) {
      case ToolChoice.Auto a -> ToolChoiceConfig.auto();
      case ToolChoice.Any a -> ToolChoiceConfig.any();
      case ToolChoice.None n -> ToolChoiceConfig.none();
      case ToolChoice.Required r -> ToolChoiceConfig.validated(r.allowedTools());
    };
  }

  private String serializeRequest(InteractionRequest request) {
    try {
      return objectMapper.writeValueAsString(request);
    } catch (Exception e) {
      throw new GeminiException("Failed to serialize request", e);
    }
  }

  HttpRequest buildHttpRequest(String jsonBody) {
    var uri = URI.create(config.effectiveBaseUrl(DEFAULT_BASE_URL) + "/interactions?alt=sse");
    var defaults = new LinkedHashMap<String, String>();
    defaults.put("Content-Type", "application/json");
    if (!Strings.isBlank(config.apiKey())) {
      defaults.put("x-goog-api-key", config.apiKey());
    }
    defaults.put("Api-Revision", API_REVISION);

    var builder =
        HttpRequest.newBuilder().uri(uri).POST(HttpRequest.BodyPublishers.ofString(jsonBody));
    for (var entry : config.effectiveHeaders(defaults).entrySet()) {
      builder.header(entry.getKey(), entry.getValue());
    }

    if (config.responseTimeout() != null) {
      builder.timeout(config.responseTimeout());
    }

    return builder.build();
  }

  /**
   * Scans a step timeline for {@code url_citation} annotations attached to text content inside
   * {@code model_output} steps. Citations are returned in document order.
   */
  static List<Citation> extractCitations(List<Step> steps) {
    if (steps == null) {
      return List.of();
    }
    var citations = new ArrayList<Citation>();
    for (var step : steps) {
      if (!step.hasTypeModelOutput() || !step.hasContent()) {
        continue;
      }
      for (var item : step.content()) {
        if (!item.hasTypeText() || !item.hasAnnotations()) {
          continue;
        }
        for (var annotation : item.annotations()) {
          if ("url_citation".equals(annotation.type())) {
            citations.add(
                Citation.newBuilder()
                    .withSourceId(annotation.url())
                    .withTitle(annotation.title())
                    .withStartIndex(annotation.startIndex())
                    .withEndIndex(annotation.endIndex())
                    .build());
          }
        }
      }
    }
    return citations.isEmpty() ? List.of() : List.copyOf(citations);
  }

  /** Tracks partial state for a streaming step keyed by its {@code index}. */
  private static final class StepState {
    String type;
    String name;
    String id;
    String signature;
    final StringBuilder argumentsBuffer = new StringBuilder();
    Map<String, Object> arguments;
  }

  static class StreamingIterator implements CloseableIterator<StreamEvent> {
    private final InputStream rawStream;
    private final BufferedReader reader;
    private final ObjectMapper objectMapper;
    private final Duration streamIdleTimeout;
    private final ExecutorService readExecutor;
    private final StringBuilder contentBuilder = new StringBuilder();
    private final List<ToolCall> toolCalls = new ArrayList<>();
    private final List<String> thoughtSignatures = new ArrayList<>();
    private final List<Citation> streamingCitations = new ArrayList<>();
    private final Map<Integer, StepState> stepStates = new LinkedHashMap<>();
    private String thinkingContent = null;
    private StreamEvent nextEvent = null;
    private boolean done = false;
    private InteractionUsage lastUsage = null;
    private String interactionId = null;
    private String terminalStatus = null;
    private InteractionResponse completeInteraction = null;

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
        throw new GeminiException(
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
      } catch (GeminiException e) {
        done = true;
        close();
        return new StreamEvent.Error(e.getMessage(), e);
      } catch (IOException e) {
        done = true;
        close();
        return new StreamEvent.Error("Stream read error", e);
      }
    }

    private StreamEvent parseStreamEvent(String json) {
      try {
        var event = objectMapper.readValue(json, StreamingEvent.class);

        if (event.hasTypeError()) {
          return handleErrorEvent(event);
        }
        if (event.hasTypeInteractionCreated() || event.hasTypeInteractionCompleted()) {
          return handleInteractionEnvelope(event);
        }
        if (event.hasTypeInteractionStatusUpdate()
            || event.hasTypeInteractionInProgress()
            || event.hasTypeInteractionRequiresAction()) {
          return handleStatusUpdate(event);
        }
        if (event.hasTypeStepStart()) {
          return handleStepStart(event);
        }
        if (event.hasTypeStepDelta()) {
          return handleStepDelta(event);
        }
        if (event.hasTypeStepStop()) {
          return handleStepStop(event);
        }
        return null;
      } catch (Exception e) {
        return new StreamEvent.Error("Failed to parse stream event", e);
      }
    }

    private StreamEvent handleErrorEvent(StreamingEvent event) {
      var message = "API error";
      int statusCode = 0;
      if (event.error() != null) {
        var msg = event.error().get("message");
        if (msg != null) {
          message = "API error: " + msg;
        }
        if (event.error().get("code") instanceof Number n) {
          statusCode = n.intValue();
        }
      }
      done = true;
      var exception =
          statusCode > 0 ? new GeminiException(message, statusCode) : new GeminiException(message);
      return new StreamEvent.Error(message, exception);
    }

    private StreamEvent handleStatusUpdate(StreamingEvent event) {
      if (event.interactionId() != null) {
        interactionId = event.interactionId();
      }
      if (event.status() != null) {
        terminalStatus = event.status();
      }
      return null;
    }

    private StreamEvent handleInteractionEnvelope(StreamingEvent event) {
      if (event.interaction() != null) {
        completeInteraction = event.interaction();
        if (completeInteraction.id() != null) {
          interactionId = completeInteraction.id();
        }
        if (completeInteraction.status() != null) {
          terminalStatus = completeInteraction.status();
        }
        if (completeInteraction.usage() != null) {
          lastUsage = completeInteraction.usage();
        }
      }
      return null;
    }

    private StreamEvent handleStepStart(StreamingEvent event) {
      if (event.index() == null || event.step() == null) {
        return null;
      }
      var state = stepStates.computeIfAbsent(event.index(), k -> new StepState());
      var step = event.step();
      state.type = step.type();
      state.name = step.name();
      state.id = step.id();
      state.signature = step.signature();
      state.arguments = step.arguments();
      if (step.hasTypeThought()) {
        registerThought(step);
        return null;
      }
      if (step.hasTypeModelOutput() && step.hasContent()) {
        return absorbInitialModelOutput(step);
      }
      return null;
    }

    /**
     * step.start for a model_output may ship initial text content (per the v2 schema example). Fold
     * every text item into {@code contentBuilder}, harvest any inline annotations, and surface the
     * concatenated initial text as a single {@link StreamEvent.TextDelta} so streaming consumers
     * see the chunk in real time.
     */
    private StreamEvent absorbInitialModelOutput(Step step) {
      var initialText = new StringBuilder();
      for (var item : step.content()) {
        if (item.hasTypeText() && item.text() != null && !item.text().isEmpty()) {
          initialText.append(item.text());
        }
        if (item.hasAnnotations()) {
          harvestCitations(item);
        }
      }
      if (initialText.length() == 0) {
        return null;
      }
      var text = initialText.toString();
      contentBuilder.append(text);
      return new StreamEvent.TextDelta(text);
    }

    private StreamEvent handleStepDelta(StreamingEvent event) {
      if (event.index() == null) {
        return null;
      }
      var state = stepStates.get(event.index());
      if (event.argumentsDelta() != null && state != null && "function_call".equals(state.type)) {
        state.argumentsBuffer.append(event.argumentsDelta());
        return null;
      }
      var delta = event.delta();
      if (delta == null) {
        return null;
      }
      if ("arguments_delta".equals(delta.type())
          && state != null
          && "function_call".equals(state.type)
          && delta.arguments() != null) {
        state.argumentsBuffer.append(delta.arguments());
        return null;
      }
      if (delta.hasTypeThoughtSignature()
          && delta.signature() != null
          && !delta.signature().isEmpty()) {
        thoughtSignatures.add(delta.signature());
        return null;
      }
      if (delta.hasTypeText() && delta.text() != null) {
        if (state != null && "thought".equals(state.type)) {
          appendThinking(delta.text());
          return new StreamEvent.ThinkingDelta(delta.text());
        }
        contentBuilder.append(delta.text());
        if (delta.hasAnnotations()) {
          harvestCitations(delta);
        }
        return new StreamEvent.TextDelta(delta.text());
      }
      if (delta.hasAnnotations()) {
        harvestCitations(delta);
      }
      return null;
    }

    private StreamEvent handleStepStop(StreamingEvent event) {
      if (event.index() == null) {
        return null;
      }
      var state = stepStates.get(event.index());
      if (state == null) {
        return null;
      }
      if ("function_call".equals(state.type)) {
        var args = finalizeArguments(state);
        var tc =
            ToolCall.newBuilder().withId(state.id).withName(state.name).withArguments(args).build();
        toolCalls.add(tc);
        return new StreamEvent.ToolCallComplete(tc);
      }
      if ("thought".equals(state.type) && thinkingContent != null && !thinkingContent.isEmpty()) {
        var signature =
            thoughtSignatures.isEmpty()
                ? null
                : thoughtSignatures.get(thoughtSignatures.size() - 1);
        return new StreamEvent.ThinkingComplete(thinkingContent, signature);
      }
      return null;
    }

    private void registerThought(Step step) {
      if (step.signature() != null && !step.signature().isEmpty()) {
        thoughtSignatures.add(step.signature());
      }
      if (step.hasSummary()) {
        for (var item : step.summary()) {
          if (item.hasTypeText() && item.text() != null && !item.text().isEmpty()) {
            appendThinking(item.text());
          }
        }
      }
    }

    private void appendThinking(String text) {
      thinkingContent = (thinkingContent == null ? "" : thinkingContent + "\n") + text;
    }

    private void harvestCitations(ContentItem item) {
      for (var annotation : item.annotations()) {
        if ("url_citation".equals(annotation.type())) {
          streamingCitations.add(
              Citation.newBuilder()
                  .withSourceId(annotation.url())
                  .withTitle(annotation.title())
                  .withStartIndex(annotation.startIndex())
                  .withEndIndex(annotation.endIndex())
                  .build());
        }
      }
    }

    private Map<String, Object> finalizeArguments(StepState state) {
      if (state.argumentsBuffer.length() > 0) {
        try {
          @SuppressWarnings("unchecked")
          var parsed =
              (Map<String, Object>)
                  objectMapper.readValue(state.argumentsBuffer.toString(), Map.class);
          if (parsed != null) {
            return parsed;
          }
        } catch (Exception ignored) {
          // Fall through to step.start arguments.
        }
      }
      return state.arguments != null ? state.arguments : Map.of();
    }

    private StreamEvent buildDoneEvent() {
      var finishReason = FinishReason.STOP;
      if (!toolCalls.isEmpty()) {
        finishReason = FinishReason.TOOL_CALLS;
      } else if ("failed".equals(terminalStatus) || "budget_exceeded".equals(terminalStatus)) {
        finishReason = FinishReason.ERROR;
      } else if ("incomplete".equals(terminalStatus)) {
        finishReason = FinishReason.LENGTH;
      }

      Response.Usage usage = null;
      if (lastUsage != null) {
        // Gemini's wire shape reports total_input_tokens as the TOTAL (cached + uncached) and
        // total_cached_tokens as a SUBSET (only on Gemini 2.5+ which enables implicit caching
        // automatically). Re-project to the disjoint Helios canonical shape: inputTokens carries
        // only the uncached portion, cacheReadInputTokens carries the cached count, cache
        // creation stays zero (Gemini does not premium implicit cache writes).
        var totalInput = lastUsage.inputTokens() != null ? lastUsage.inputTokens() : 0;
        var totalOutput = lastUsage.outputTokens() != null ? lastUsage.outputTokens() : 0;
        var cached = lastUsage.cachedTokensOrZero();
        // Defensive clamp: a server-side accounting bug reporting cached > total_input would
        // produce a negative uncached value in naive arithmetic. Under-report uncached rather
        // than emit a nonsensical negative count.
        var uncachedInput = Math.max(0, totalInput - cached);
        usage = Response.Usage.of(uncachedInput, totalOutput, 0, cached);
      }

      var citations =
          streamingCitations.isEmpty() ? List.<Citation>of() : List.copyOf(streamingCitations);

      var metadata = new HashMap<String, String>();
      if (!thoughtSignatures.isEmpty()) {
        metadata.put(THOUGHT_SIGNATURES_KEY, String.join(SIGNATURE_DELIMITER, thoughtSignatures));
      }
      if (interactionId != null) {
        metadata.put(INTERACTION_ID_KEY, interactionId);
      }

      var response =
          Response.newBuilder()
              .withContent(contentBuilder.toString())
              .withToolCalls(toolCalls.isEmpty() ? List.<ToolCall>of() : List.copyOf(toolCalls))
              .withFinishReason(finishReason)
              .withUsage(usage)
              .withThinking(thinkingContent)
              .withCitations(citations)
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
        // Stream may already be closed by the executor cancellation.
      }
      try {
        reader.close();
      } catch (IOException ignored) {
        // Reader may already be closed via the underlying stream.
      }
    }
  }
}
