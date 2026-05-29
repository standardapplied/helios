/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import ai.singlr.anthropic.api.ApiStreamEvent;
import ai.singlr.anthropic.api.CacheControl;
import ai.singlr.anthropic.api.ContentBlock;
import ai.singlr.anthropic.api.ContentDelta;
import ai.singlr.anthropic.api.MessagesRequest;
import ai.singlr.anthropic.api.OutputConfig;
import ai.singlr.anthropic.api.SystemContent;
import ai.singlr.anthropic.api.ThinkingConfig;
import ai.singlr.anthropic.api.ToolChoiceConfig;
import ai.singlr.anthropic.api.ToolDefinition;
import ai.singlr.core.common.HttpClientFactory;
import ai.singlr.core.common.Strings;
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
 * Anthropic Claude model implementation using the Messages API.
 *
 * <p>All requests use SSE streaming internally for robust timeout handling. Synchronous {@link
 * #chat} methods stream under the hood and accumulate the response, avoiding HTTP read timeouts on
 * long-running generations. A per-line idle timeout detects stalled streams and throws a retryable
 * {@link AnthropicException}.
 */
public class AnthropicModel implements Model {

  private static final String PROVIDER_NAME = "anthropic";
  static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1/messages";
  private static final String API_VERSION = "2023-06-01";

  /**
   * Output-token ceiling assumed for a Claude model ID this build does not recognise (one not in
   * {@link AnthropicModelId}). Matches the current Opus ceiling — a sane non-zero default so an
   * unrecognised model's requests aren't rejected for {@code max_tokens=0}. Callers override via
   * {@link ModelConfig.Builder#withMaxOutputTokens(Integer)}.
   */
  static final int DEFAULT_MAX_OUTPUT_TOKENS = 32_000;

  static final String THINKING_KEY = "anthropic.thinking";
  static final String THINKING_SIGNATURE_KEY = "anthropic.thinkingSignature";

  /**
   * Metadata key carrying every thinking block in the message as a JSON array of {@code
   * [{"text":"…","signature":"…"}, …]}. Single-block messages also set the legacy {@link
   * #THINKING_KEY} / {@link #THINKING_SIGNATURE_KEY} for backward compatibility. Multi-block
   * messages set this key only; round-tripping any other shape would require fabricating a single
   * signature across blocks, which the Anthropic API rejects.
   */
  static final String THINKING_BLOCKS_KEY = "anthropic.thinkingBlocks";

  private final String wireModelId;
  private final AnthropicModelId knownModel;
  private final boolean usesAdaptiveThinking;
  private final ModelConfig config;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final CachePolicy cachePolicy;

  AnthropicModel(AnthropicModelId modelId, ModelConfig config) {
    this(modelId, config, CachePolicy.shortLived());
  }

  AnthropicModel(AnthropicModelId modelId, ModelConfig config, CachePolicy cachePolicy) {
    this(modelId != null ? modelId.id() : null, modelId, config, cachePolicy);
  }

  AnthropicModel(String wireModelId, ModelConfig config) {
    this(wireModelId, AnthropicModelId.fromId(wireModelId), config, CachePolicy.shortLived());
  }

  private AnthropicModel(
      String wireModelId,
      AnthropicModelId knownModel,
      ModelConfig config,
      CachePolicy cachePolicy) {
    if (Strings.isBlank(wireModelId)) {
      throw new IllegalArgumentException("modelId is required");
    }
    if (config == null) {
      throw new IllegalArgumentException("config is required");
    }
    if (cachePolicy == null) {
      throw new IllegalArgumentException("cachePolicy is required");
    }
    var hasCustomEndpoint = !Strings.isBlank(config.baseUrl());
    if (!hasCustomEndpoint && Strings.isBlank(config.apiKey())) {
      throw new IllegalArgumentException(
          "config with valid apiKey is required (or set baseUrl + auth header)");
    }
    this.wireModelId = wireModelId;
    this.knownModel = knownModel;
    // Unrecognised Claude IDs default to the adaptive thinking shape: new releases adopt it, and
    // it is where the family is converging. Known models keep their recorded shape.
    this.usesAdaptiveThinking = knownModel == null || knownModel.usesAdaptiveThinking();
    this.config = config;
    this.cachePolicy = cachePolicy;
    this.httpClient = HttpClientFactory.create(config);
    this.objectMapper =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  }

  /**
   * The {@link CachePolicy} that shapes outgoing requests — exposed for diagnostics, traces, and
   * tests that verify request shaping.
   *
   * @return the configured policy; non-null
   */
  public CachePolicy cachePolicy() {
    return cachePolicy;
  }

  /**
   * Convenience accessor: whether the policy emits {@code cache_control} breakpoints.
   *
   * @return {@code true} for short-lived or long-lived policies; {@code false} for disabled
   */
  public boolean promptCachingEnabled() {
    return cachePolicy.enabled();
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
    if (config.contextWindow() != null) {
      return config.contextWindow();
    }
    return knownModel != null ? knownModel.contextWindow() : 0;
  }

  @Override
  public int maxOutputTokens() {
    return knownModel != null ? knownModel.maxOutputTokens() : DEFAULT_MAX_OUTPUT_TOKENS;
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
    // text-only turn. Parsing the incidental prose (model's preamble before the tool_use block)
    // as JSON throws and kills the session before the loop ever dispatches the tool. The schema
    // still rides the request via the system instruction; the gate is on the response side only.
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
    } catch (AnthropicException e) {
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

  private StreamingIterator openStream(MessagesRequest request)
      throws IOException, InterruptedException {
    var jsonBody = serializeRequest(request);
    var httpRequest = buildHttpRequest(jsonBody);
    var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
    if (httpResponse.statusCode() != 200) {
      try (var body = httpResponse.body()) {
        var errorBody = HttpClientFactory.readBoundedErrorBody(body);
        throw new AnthropicException(
            "API error (status " + httpResponse.statusCode() + "): " + errorBody,
            httpResponse.statusCode());
      }
    }
    return new StreamingIterator(httpResponse, objectMapper, config.streamIdleTimeout());
  }

  /**
   * Drive {@link #openStream(MessagesRequest)} and {@link #drainToResponse(StreamingIterator)},
   * promoting transport-layer failures to the typed signals the session loop's retry policy
   * understands.
   *
   * <p>Mapping:
   *
   * <ul>
   *   <li>{@link TransientStreamException} from {@link #drainToResponse} (mid-stream socket drop
   *       after a 200 response) — propagated unchanged so the loop retries.
   *   <li>{@link AnthropicException} from {@link #openStream} (non-200 response parsed and wrapped
   *       with status code) — propagated unchanged so non-stream protocol errors keep their
   *       existing error path.
   *   <li>{@link IOException} from {@link #openStream} (connect-time failure: DNS, TCP reset,
   *       half-closed pre-handshake) — promoted to {@link TransientStreamException} so the loop
   *       retries. Matches {@link AnthropicException#isRetryable()} which classifies {@code status
   *       == 0} network errors as retryable.
   *   <li>{@link InterruptedException} — wrapped as a non-retryable {@link AnthropicException} so
   *       the caller can clean up rather than spinning on retries.
   * </ul>
   */
  private Response<Void> streamAndDrain(MessagesRequest request) {
    try (var iterator = openStream(request)) {
      return drainToResponse(iterator);
    } catch (AnthropicException | TransientStreamException e) {
      throw e;
    } catch (IOException e) {
      throw new TransientStreamException(
          "Failed to communicate with Anthropic API", e, PROVIDER_NAME);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AnthropicException("Request interrupted", e);
    }
  }

  /**
   * Drain the SSE iterator into a final {@link Response}, mapping {@link StreamEvent.Error} events
   * into provider exceptions.
   *
   * <p>Routing:
   *
   * <ul>
   *   <li>Cause is {@link AnthropicException} — rethrown verbatim (HTTP-side failures parsed from a
   *       non-200 response retain their status code).
   *   <li>Cause is {@link IOException} (typed signal that the SSE socket dropped mid-stream after a
   *       200 response) — promoted to {@link TransientStreamException} so the agent loop's bounded
   *       retry can re-issue the turn; the {@link IOException} is preserved as {@link
   *       Throwable#getCause()} so the session terminal can walk the chain.
   *   <li>Cause is anything else, or {@code null} (the API-side {@code event: error} path carries
   *       {@code null}) — rewrapped in {@link AnthropicException} with the original cause for
   *       backwards compatibility with non-stream error paths.
   * </ul>
   */
  @SuppressWarnings("unchecked")
  private Response<Void> drainToResponse(StreamingIterator iterator) {
    while (iterator.hasNext()) {
      var event = iterator.next();
      if (event instanceof StreamEvent.Done(var response)) {
        return (Response<Void>) response;
      }
      if (event instanceof StreamEvent.Error(String message, Exception cause)) {
        if (cause instanceof AnthropicException ae) {
          throw ae;
        }
        if (cause instanceof IOException) {
          throw new TransientStreamException(message, cause, PROVIDER_NAME);
        }
        throw new AnthropicException(message, cause);
      }
    }
    throw new AnthropicException("Stream ended without completion event");
  }

  MessagesRequest buildRequest(
      List<Message> messages, List<Tool> tools, Map<String, Object> outputSchema) {
    var apiMessages = new ArrayList<MessagesRequest.MessageEntry>();
    String systemInstruction = null;

    for (int i = 0; i < messages.size(); i++) {
      var message = messages.get(i);
      switch (message.role()) {
        case SYSTEM -> systemInstruction = appendSystemText(systemInstruction, message.content());
        case USER -> apiMessages.add(convertUserMessage(message));
        case ASSISTANT -> apiMessages.add(convertAssistantMessage(message));
        case TOOL -> {
          var toolResults = new ArrayList<ContentBlock>();
          toolResults.add(ContentBlock.toolResult(message.toolCallId(), message.content()));
          while (i + 1 < messages.size() && messages.get(i + 1).role() == Role.TOOL) {
            i++;
            var next = messages.get(i);
            toolResults.add(ContentBlock.toolResult(next.toolCallId(), next.content()));
          }
          apiMessages.add(MessagesRequest.MessageEntry.user(toolResults));
        }
      }
    }

    if (outputSchema != null) {
      var schemaJson = serializeValue(outputSchema);
      // Two phrasings — the tool-using variant acknowledges the loop so the schema instruction
      // doesn't fight the deployer's "use tools first, then emit JSON" guidance every turn.
      // Without this contextualisation the loudest-instruction-wins effect causes Claude to skip
      // tool dispatch on turn 0 and emit prose that fails downstream parse gating.
      var instruction =
          (tools == null || tools.isEmpty())
              ? "You must respond with valid JSON matching this schema:\n"
                  + schemaJson
                  + "\nDo not wrap the JSON in markdown code blocks. Output only the raw JSON."
              : "You may call the available tools to gather information."
                  + " When you are ready to emit your final answer (not a tool call),"
                  + " it must be valid JSON matching this schema:\n"
                  + schemaJson
                  + "\nDo not wrap the JSON in markdown code blocks. Output only the raw JSON.";
      systemInstruction = appendSystemText(systemInstruction, instruction);
    }

    List<ToolDefinition> toolDefs = null;
    if (tools != null && !tools.isEmpty()) {
      toolDefs =
          tools.stream()
              .map(t -> new ToolDefinition(t.name(), t.description(), t.parametersAsJsonSchema()))
              .toList();
    }

    var toolChoiceConfig = buildToolChoice(tools);
    var thinkingSpec = buildThinkingSpec();

    int maxTokens = config.maxOutputTokens() != null ? config.maxOutputTokens() : maxOutputTokens();
    if (thinkingSpec.thinking() != null && thinkingSpec.thinking().budgetTokens() != null) {
      maxTokens = Math.max(maxTokens, thinkingSpec.thinking().budgetTokens() + 1024);
    }

    // Both shapes (legacy enabled and adaptive) override temperature when thinking is on —
    // Anthropic's API rejects temperature alongside any active thinking config.
    Double temperature = config.temperature();
    if (thinkingSpec.thinking() != null && !"disabled".equals(thinkingSpec.thinking().type())) {
      temperature = null;
    }

    var builder =
        MessagesRequest.newBuilder()
            .withModel(wireModelId)
            .withMaxTokens(maxTokens)
            .withMessages(apiMessages)
            .withStream(true)
            .withToolChoice(toolChoiceConfig)
            .withTemperature(temperature)
            .withTopP(config.topP())
            .withStopSequences(config.stopSequences())
            .withThinking(thinkingSpec.thinking())
            .withOutputConfig(thinkingSpec.outputConfig());

    if (cachePolicy.enabled()) {
      var breakpoint = cachePolicy.breakpoint();
      applySystemWithCache(builder, systemInstruction, breakpoint);
      builder.withTools(withCachedTail(toolDefs, breakpoint));
      annotateLastMessageForCaching(apiMessages, breakpoint);
    } else {
      builder.withSystem(systemInstruction);
      builder.withTools(toolDefs);
    }

    return builder.build();
  }

  /**
   * Promote a non-blank system string to the cache-aware array shape with a single ephemeral
   * breakpoint on the only block; pass plain string (or {@code null}) when caching is disabled or
   * when there is no system prompt. The Anthropic API only respects {@code cache_control} on the
   * array shape, so the legacy string form silently misses cache hits even with caching enabled.
   */
  static void applySystemWithCache(
      MessagesRequest.Builder builder, String systemInstruction, CacheControl breakpoint) {
    if (Strings.isBlank(systemInstruction)) {
      builder.withSystem((String) null);
      return;
    }
    builder.withSystem(List.of(SystemContent.text(systemInstruction).withCacheControl(breakpoint)));
  }

  /**
   * Return a copy of {@code defs} with the last tool annotated with {@code cache_control}. The
   * Anthropic server treats the cache breakpoint as anchored to the end of the tools array; one
   * breakpoint covers the entire section. Empty / null input passes through untouched.
   */
  static List<ToolDefinition> withCachedTail(List<ToolDefinition> defs, CacheControl breakpoint) {
    if (defs == null || defs.isEmpty()) {
      return defs;
    }
    var copy = new ArrayList<ToolDefinition>(defs.size());
    for (var i = 0; i < defs.size() - 1; i++) {
      copy.add(defs.get(i));
    }
    copy.add(defs.getLast().withCacheControl(breakpoint));
    return List.copyOf(copy);
  }

  /**
   * Mark the last (and second-to-last when present) message with a {@code cache_control}
   * breakpoint. Together with the system and tools breakpoints this exhausts Anthropic's
   * 4-breakpoint budget on the canonical agent-loop shape — the intended use of the budget.
   *
   * <h2>Why the second-to-last breakpoint matters</h2>
   *
   * Anthropic enforces a per-breakpoint <b>20-block lookback window</b> when resolving cache
   * prefixes. A conversation that grows past 20 blocks of history (typical after ~5 agent turns
   * with multi-tool-call rounds) makes the single last-message breakpoint blind to the system +
   * tools cache prefix — every turn becomes a full cache miss.
   *
   * <p>Annotating the second-to-last message gives the cache a stable rolling write that the next
   * turn's lookback can find: turn N writes prefixes at penultimate-msg-N AND last-msg-N; turn
   * N+1's penultimate becomes turn N's last, and lookback chains cleanly.
   *
   * <p>String-content messages are promoted to a single-text-block list so the {@code
   * cache_control} field has a block to attach to (Anthropic does not accept {@code cache_control}
   * on a plain-string {@code content}). Empty-string and empty-list messages are skipped per slot —
   * we never synthesize empty cache blocks.
   */
  static void annotateLastMessageForCaching(
      List<MessagesRequest.MessageEntry> apiMessages, CacheControl breakpoint) {
    if (apiMessages == null || apiMessages.isEmpty()) {
      return;
    }
    var lastIdx = apiMessages.size() - 1;
    annotateMessageEntryForCaching(apiMessages, lastIdx, breakpoint);
    if (apiMessages.size() >= 2) {
      annotateMessageEntryForCaching(apiMessages, lastIdx - 1, breakpoint);
    }
  }

  /**
   * Attach {@code breakpoint} to the last block of the message at {@code idx}, promoting a
   * string-content message to single-block form first. Idempotent — re-annotating a block that
   * already carries {@code cache_control} replaces it with the new breakpoint.
   */
  @SuppressWarnings("unchecked")
  private static void annotateMessageEntryForCaching(
      List<MessagesRequest.MessageEntry> apiMessages, int idx, CacheControl breakpoint) {
    var entry = apiMessages.get(idx);
    if (entry.content() instanceof String text) {
      if (text.isEmpty()) {
        return;
      }
      var block = ContentBlock.text(text).withCacheControl(breakpoint);
      apiMessages.set(idx, new MessagesRequest.MessageEntry(entry.role(), List.of(block)));
      return;
    }
    if (entry.content() instanceof List<?> raw && !raw.isEmpty()) {
      var blocks = (List<ContentBlock>) raw;
      var newBlocks = new ArrayList<ContentBlock>(blocks.size());
      for (var i = 0; i < blocks.size() - 1; i++) {
        newBlocks.add(blocks.get(i));
      }
      newBlocks.add(blocks.getLast().withCacheControl(breakpoint));
      apiMessages.set(idx, new MessagesRequest.MessageEntry(entry.role(), List.copyOf(newBlocks)));
    }
  }

  private static String appendSystemText(String existing, String additional) {
    if (existing == null) {
      return additional;
    }
    return existing + "\n\n" + additional;
  }

  private static MessagesRequest.MessageEntry convertUserMessage(Message message) {
    var text = message.content() != null ? message.content() : "";
    if (!message.hasInlineFiles()) {
      return MessagesRequest.MessageEntry.user(text);
    }
    var blocks = new ArrayList<ContentBlock>(message.inlineFiles().size() + 1);
    for (var file : message.inlineFiles()) {
      var data = Base64.getEncoder().encodeToString(file.data());
      var media = file.mimeType();
      if ("application/pdf".equals(media)) {
        blocks.add(ContentBlock.document(media, data));
      } else if (media != null && media.startsWith("image/")) {
        blocks.add(ContentBlock.image(media, data));
      } else {
        // Text-shaped / unsupported binary — inline as a fenced text block so the model sees the
        // content. The provider doesn't have a generic "file" content type the way OpenAI does, so
        // we fall back to text. Empty data is rejected upstream.
        var body = new String(file.data(), StandardCharsets.UTF_8);
        blocks.add(ContentBlock.text("[attachment " + media + "]\n" + body));
      }
    }
    if (!text.isEmpty()) {
      blocks.add(ContentBlock.text(text));
    }
    return MessagesRequest.MessageEntry.user(blocks);
  }

  static MessagesRequest.MessageEntry convertAssistantMessage(Message message) {
    var thinkingBlocks = decodeThinkingBlocks(message);
    if (!message.hasToolCalls() && thinkingBlocks.isEmpty()) {
      return MessagesRequest.MessageEntry.assistant(
          message.content() != null ? message.content() : "");
    }

    var blocks = new ArrayList<ContentBlock>();
    for (var tb : thinkingBlocks) {
      blocks.add(ContentBlock.thinking(tb.text(), tb.signature()));
    }

    if (message.content() != null && !message.content().isEmpty()) {
      blocks.add(ContentBlock.text(message.content()));
    }

    for (var tc : message.toolCalls()) {
      blocks.add(ContentBlock.toolUse(tc.id(), tc.name(), tc.arguments()));
    }

    return MessagesRequest.MessageEntry.assistant(blocks);
  }

  /**
   * Recover every thinking block recorded on the message. Prefers the {@link #THINKING_BLOCKS_KEY}
   * JSON-array shape (used by 1.4+ multi-block messages), then falls back to the legacy single
   * {@link #THINKING_KEY} / {@link #THINKING_SIGNATURE_KEY} pair. Returns an empty list when no
   * thinking signature is present.
   */
  static List<ThinkingBlock> decodeThinkingBlocks(Message message) {
    if (message.metadata() == null) {
      return List.of();
    }
    var encoded = message.metadata().get(THINKING_BLOCKS_KEY);
    if (encoded != null && !encoded.isEmpty()) {
      try {
        var mapper = JsonMapper.builder().build();
        @SuppressWarnings("unchecked")
        var raw = (List<Map<String, Object>>) mapper.readValue(encoded, List.class);
        var out = new ArrayList<ThinkingBlock>(raw.size());
        for (var entry : raw) {
          var text = entry.get("text") == null ? "" : entry.get("text").toString();
          var signature = entry.get("signature") == null ? "" : entry.get("signature").toString();
          if (!signature.isEmpty()) {
            out.add(new ThinkingBlock(text, signature));
          }
        }
        return out;
      } catch (Exception ignored) {
        // Fall through to legacy single-block path.
      }
    }
    var signature = message.metadata().get(THINKING_SIGNATURE_KEY);
    if (signature != null && !signature.isEmpty()) {
      var text = message.metadata().getOrDefault(THINKING_KEY, "");
      return List.of(new ThinkingBlock(text, signature));
    }
    return List.of();
  }

  /** One thinking content block — text plus its content-block-scoped Anthropic signature. */
  record ThinkingBlock(String text, String signature) {}

  private ToolChoiceConfig buildToolChoice(List<Tool> tools) {
    if (config.toolChoice() == null) {
      return null;
    }

    return switch (config.toolChoice()) {
      case ToolChoice.Auto a -> ToolChoiceConfig.auto();
      case ToolChoice.Any a -> ToolChoiceConfig.any();
      case ToolChoice.None n -> null;
      case ToolChoice.Required r -> {
        if (r.allowedTools().size() > 1) {
          throw new IllegalStateException(
              "Claude tool choice supports only a single tool name, got: " + r.allowedTools());
        }
        yield ToolChoiceConfig.tool(r.allowedTools().iterator().next());
      }
    };
  }

  /**
   * Translate {@link ThinkingLevel} into the Anthropic API request shape, dispatching by model.
   * Opus 4.7+ and any unrecognised Claude ID use {@code thinking.type=adaptive} + {@code
   * output_config.effort=...}; legacy Opus 4.6 / Sonnet 4.6 use {@code thinking.type=enabled} +
   * {@code budget_tokens=...}.
   *
   * @return both the {@link ThinkingConfig} and any sibling {@link OutputConfig} that must ride on
   *     the request; either may be {@code null} when thinking is disabled
   */
  private ThinkingSpec buildThinkingSpec() {
    if (config.thinkingLevel() == null || config.thinkingLevel() == ThinkingLevel.NONE) {
      return new ThinkingSpec(null, null);
    }

    if (usesAdaptiveThinking) {
      var effort =
          switch (config.thinkingLevel()) {
            case NONE -> null;
            case MINIMAL, LOW -> OutputConfig.LOW;
            case MEDIUM -> OutputConfig.MEDIUM;
            case HIGH -> OutputConfig.HIGH;
            case XHIGH -> OutputConfig.XHIGH;
            case MAX -> OutputConfig.MAX;
          };
      return new ThinkingSpec(ThinkingConfig.adaptive(), effort);
    }

    if (config.thinkingLevel() == ThinkingLevel.XHIGH) {
      throw new IllegalArgumentException(
          "ThinkingLevel.XHIGH requires an adaptive-capable model (Opus 4.7+); model "
              + wireModelId
              + " uses the legacy enabled+budget_tokens shape which has no 'xhigh' equivalent.");
    }
    if (config.thinkingLevel() == ThinkingLevel.MAX) {
      throw new IllegalArgumentException(
          "ThinkingLevel.MAX requires an adaptive-capable model (Opus 4.7+); model "
              + wireModelId
              + " uses the legacy enabled+budget_tokens shape which has no 'max' equivalent.");
    }
    var budgetTokens =
        switch (config.thinkingLevel()) {
          case NONE -> 0;
          case MINIMAL -> 1024;
          case LOW -> 4096;
          case MEDIUM -> 10000;
          case HIGH -> 32000;
          case XHIGH, MAX ->
              throw new IllegalStateException("XHIGH/MAX handled above; unreachable");
        };
    return new ThinkingSpec(ThinkingConfig.enabled(budgetTokens), null);
  }

  /**
   * Pair of thinking-related request fields. Translation in {@link #buildThinkingSpec} produces
   * exactly the right combination: legacy models get a {@code ThinkingConfig} alone; adaptive
   * models get a {@code ThinkingConfig} plus a sibling {@code OutputConfig}; thinking-disabled runs
   * get nulls in both slots.
   */
  private record ThinkingSpec(ThinkingConfig thinking, OutputConfig outputConfig) {}

  private String serializeRequest(MessagesRequest request) {
    try {
      return objectMapper.writeValueAsString(request);
    } catch (Exception e) {
      throw new AnthropicException("Failed to serialize request", e);
    }
  }

  private String serializeValue(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new AnthropicException("Failed to serialize value", e);
    }
  }

  HttpRequest buildHttpRequest(String jsonBody) {
    var defaults = new LinkedHashMap<String, String>();
    defaults.put("Content-Type", "application/json");
    if (!Strings.isBlank(config.apiKey())) {
      defaults.put("x-api-key", config.apiKey());
    }
    defaults.put("anthropic-version", API_VERSION);
    var builder =
        HttpRequest.newBuilder()
            .uri(URI.create(config.effectiveBaseUrl(DEFAULT_BASE_URL)))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
    for (var entry : config.effectiveHeaders(defaults).entrySet()) {
      builder.header(entry.getKey(), entry.getValue());
    }

    if (config.responseTimeout() != null) {
      builder.timeout(config.responseTimeout());
    }

    return builder.build();
  }

  static FinishReason mapStopReason(String stopReason) {
    if (stopReason == null) {
      return FinishReason.STOP;
    }
    return switch (stopReason) {
      case "end_turn", "stop_sequence" -> FinishReason.STOP;
      case "tool_use" -> FinishReason.TOOL_CALLS;
      case "max_tokens" -> FinishReason.LENGTH;
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
    private final Map<Integer, ToolCallAccumulator> toolCallAccumulators = new HashMap<>();
    // Per-content-block thinking accumulators keyed by block index. Each thinking block in a
    // multi-block message carries its own Anthropic signature; concatenating them into a single
    // buffer (the prior shape) yields a signature the API rejects on the next turn.
    private final LinkedHashMap<Integer, ThinkingAccumulator> thinkingAccumulators =
        new LinkedHashMap<>();
    private StreamEvent nextEvent = null;
    private boolean done = false;
    private int inputTokens = 0;
    private int outputTokens = 0;
    private int cacheCreationInputTokens = 0;
    private int cacheReadInputTokens = 0;
    private String stopReason = null;

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
        throw new AnthropicException(
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
      } catch (AnthropicException e) {
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
        var event = objectMapper.readValue(json, ApiStreamEvent.class);

        if (event.hasTypeMessageStart()) {
          if (event.message() != null && event.message().usage() != null) {
            var usage = event.message().usage();
            if (usage.inputTokens() != null) {
              inputTokens = usage.inputTokens();
            }
            // Cache tokens are first reported on message_start (initial accounting of cache
            // writes/reads triggered by this turn's prefix). message_delta later carries the
            // running output_tokens; cache counts do not change after start.
            if (usage.cacheCreationInputTokens() != null) {
              cacheCreationInputTokens = usage.cacheCreationInputTokens();
            }
            if (usage.cacheReadInputTokens() != null) {
              cacheReadInputTokens = usage.cacheReadInputTokens();
            }
          }
          return null;
        }

        if (event.hasTypeContentBlockStart()) {
          if (event.contentBlock() != null && event.contentBlock().hasTypeToolUse()) {
            toolCallAccumulators.put(
                event.index(),
                new ToolCallAccumulator(
                    event.contentBlock().id(), event.contentBlock().name(), new StringBuilder()));
          } else if (event.contentBlock() != null && event.contentBlock().hasTypeThinking()) {
            thinkingAccumulators.put(
                event.index(), new ThinkingAccumulator(new StringBuilder(), new StringBuilder()));
          }
          return null;
        }

        if (event.hasTypeContentBlockDelta() && event.delta() != null) {
          return handleContentBlockDelta(event.index(), event.delta());
        }

        if (event.hasTypeContentBlockStop()) {
          return handleContentBlockStop(event.index());
        }

        if (event.hasTypeMessageDelta()) {
          if (event.delta() != null && event.delta().stopReason() != null) {
            stopReason = event.delta().stopReason();
          }
          if (event.usage() != null && event.usage().outputTokens() != null) {
            outputTokens = event.usage().outputTokens();
          }
          return null;
        }

        if (event.hasTypeMessageStop()) {
          done = true;
          close();
          return buildDoneEvent();
        }

        if (event.hasTypeError()) {
          return new StreamEvent.Error("API stream error: " + json, null);
        }

        return null;
      } catch (Exception e) {
        return new StreamEvent.Error("Failed to parse stream event", e);
      }
    }

    private StreamEvent handleContentBlockDelta(Integer index, ContentDelta delta) {
      if (delta.hasTypeTextDelta() && delta.text() != null) {
        contentBuilder.append(delta.text());
        return new StreamEvent.TextDelta(delta.text());
      }

      if (delta.hasTypeInputJsonDelta() && delta.partialJson() != null && index != null) {
        var accumulator = toolCallAccumulators.get(index);
        if (accumulator != null) {
          accumulator.jsonBuilder().append(delta.partialJson());
        }
        return null;
      }

      if (delta.hasTypeThinkingDelta() && delta.thinking() != null && index != null) {
        // Anthropic may emit thinking_delta events before the corresponding content_block_start
        // (rare but seen in practice). Lazily create the accumulator so we never lose a delta.
        thinkingAccumulators
            .computeIfAbsent(
                index, i -> new ThinkingAccumulator(new StringBuilder(), new StringBuilder()))
            .text()
            .append(delta.thinking());
        // Surface each delta as a token-level event so live UIs can render the model's reasoning
        // as it arrives, not only as the aggregated terminal block.
        return new StreamEvent.ThinkingDelta(delta.thinking());
      }

      if (delta.hasTypeSignatureDelta() && delta.signature() != null && index != null) {
        thinkingAccumulators
            .computeIfAbsent(
                index, i -> new ThinkingAccumulator(new StringBuilder(), new StringBuilder()))
            .signature()
            .append(delta.signature());
        return null;
      }

      return null;
    }

    @SuppressWarnings("unchecked")
    private StreamEvent handleContentBlockStop(Integer index) {
      if (index == null) {
        return null;
      }
      // Thinking block closing: surface the terminal aggregation so consumers can capture the
      // full reasoning text plus replay signature (used for prefix-cache reuse on subsequent
      // turns). We keep the accumulator in place — buildDoneEvent reads it for Response.thinking.
      var thinking = thinkingAccumulators.get(index);
      if (thinking != null) {
        var fullText = thinking.text().toString();
        var signature = thinking.signature().length() == 0 ? null : thinking.signature().toString();
        if (Strings.isBlank(fullText)) {
          return null;
        }
        return new StreamEvent.ThinkingComplete(fullText, signature);
      }
      var accumulator = toolCallAccumulators.remove(index);
      if (accumulator == null) {
        return null;
      }

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
              .withId(accumulator.id())
              .withName(accumulator.name())
              .withArguments(arguments)
              .build();
      toolCalls.add(tc);
      return new StreamEvent.ToolCallComplete(tc);
    }

    private StreamEvent buildDoneEvent() {
      var content = contentBuilder.toString();
      var calls = toolCalls.isEmpty() ? List.<ToolCall>of() : List.copyOf(toolCalls);

      var finishReason = mapStopReason(stopReason);
      if (!calls.isEmpty() && finishReason != FinishReason.TOOL_CALLS) {
        finishReason = FinishReason.TOOL_CALLS;
      }

      Response.Usage usage = null;
      if (inputTokens > 0
          || outputTokens > 0
          || cacheCreationInputTokens > 0
          || cacheReadInputTokens > 0) {
        usage =
            Response.Usage.of(
                inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens);
      }

      // Collect every thinking block in arrival order. The LinkedHashMap iteration order matches
      // the Anthropic content-block index sequence, which the API expects on the next turn.
      var thinkingBlocks = new ArrayList<ThinkingBlock>(thinkingAccumulators.size());
      var combinedThinking = new StringBuilder();
      for (var acc : thinkingAccumulators.values()) {
        var sigStr = acc.signature().toString();
        if (sigStr.isEmpty()) {
          continue; // Signature-less thinking blocks cannot round-trip; skip.
        }
        var textStr = acc.text().toString();
        thinkingBlocks.add(new ThinkingBlock(textStr, sigStr));
        if (!combinedThinking.isEmpty()) {
          combinedThinking.append("\n\n");
        }
        combinedThinking.append(textStr);
      }
      String thinking = combinedThinking.isEmpty() ? null : combinedThinking.toString();

      var metadata = new HashMap<String, String>();
      if (!thinkingBlocks.isEmpty()) {
        try {
          var arr = new ArrayList<Map<String, String>>(thinkingBlocks.size());
          for (var tb : thinkingBlocks) {
            arr.add(Map.of("text", tb.text(), "signature", tb.signature()));
          }
          metadata.put(THINKING_BLOCKS_KEY, objectMapper.writeValueAsString(arr));
        } catch (Exception ignored) {
          // Encoding failure is non-fatal; the legacy single-block keys below are the fallback.
        }
        // Legacy single-block keys — preserved when exactly one thinking block was seen, so older
        // consumers (and tests) continue to observe the same metadata shape.
        if (thinkingBlocks.size() == 1) {
          metadata.put(THINKING_KEY, thinkingBlocks.getFirst().text());
          metadata.put(THINKING_SIGNATURE_KEY, thinkingBlocks.getFirst().signature());
        }
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

    private record ToolCallAccumulator(String id, String name, StringBuilder jsonBuilder) {}

    /**
     * Per-content-block thinking accumulator. {@code text} buffers thinking deltas; {@code
     * signature} buffers signature deltas. Each thinking block in a multi-block message gets its
     * own pair so the Anthropic-issued signature stays attached to the matching text.
     */
    private record ThinkingAccumulator(StringBuilder text, StringBuilder signature) {}
  }
}
