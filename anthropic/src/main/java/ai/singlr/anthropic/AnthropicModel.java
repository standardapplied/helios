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
import java.util.TreeMap;
import java.util.TreeSet;
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

  /**
   * Metadata key carrying the assistant turn's full content-block array as raw JSON, set whenever
   * the turn used Anthropic server tools (web search / web fetch). Those blocks — including each
   * result's {@code encrypted_content} — must be echoed back <b>verbatim</b> on later turns or the
   * API rejects the request with a 400; {@link #convertAssistantMessage} replays this array as the
   * message content when present.
   */
  static final String RAW_CONTENT_KEY = "anthropic.rawContent";

  /** Metadata key carrying the provider's raw {@code stop_reason} string. */
  static final String STOP_REASON_KEY = "anthropic.stopReason";

  /**
   * Ceiling on automatic {@code pause_turn} continuations within one logical turn. The API pauses
   * long server-tool turns (default server loop is ~10 iterations); each continuation re-sends the
   * paused assistant content and resumes. The bound turns a pathological pause loop into a loud
   * failure instead of an infinite spin.
   */
  static final int MAX_PAUSE_CONTINUATIONS = 8;

  private static final String PAUSE_TURN = "pause_turn";

  private final String wireModelId;
  private final AnthropicModelId knownModel;
  private final AnthropicModelId.ThinkingShape thinkingShape;
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
    this.thinkingShape =
        knownModel != null ? knownModel.thinkingShape() : AnthropicModelId.ThinkingShape.ADAPTIVE;
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
      return new PauseContinuingIterator(request);
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

  /**
   * {@link CloseableIterator} facade that splices {@code pause_turn} continuations into one
   * seamless event stream: the paused segment's {@code Done} is swallowed, a continuation stream
   * opens with the paused content echoed verbatim, and the final {@code Done} carries the merged
   * response. Consumers never observe the pause. Bounded by {@link #MAX_PAUSE_CONTINUATIONS}.
   */
  private final class PauseContinuingIterator implements CloseableIterator<StreamEvent> {

    private final MessagesRequest baseRequest;
    private StreamingIterator current;
    private Response<Void> mergedSoFar;
    private int continuations;

    PauseContinuingIterator(MessagesRequest baseRequest) throws IOException, InterruptedException {
      this.baseRequest = baseRequest;
      this.current = openStream(baseRequest);
    }

    @Override
    public boolean hasNext() {
      return current.hasNext();
    }

    @Override
    @SuppressWarnings("unchecked")
    public StreamEvent next() {
      while (true) {
        var event = current.next();
        if (!(event instanceof StreamEvent.Done done)) {
          return event;
        }
        var segment = (Response<Void>) done.response();
        var merged = mergedSoFar == null ? segment : mergeSegments(mergedSoFar, segment);
        if (!PAUSE_TURN.equals(merged.metadata().get(STOP_REASON_KEY))) {
          return new StreamEvent.Done(merged);
        }
        if (continuations >= MAX_PAUSE_CONTINUATIONS) {
          return new StreamEvent.Error(
              "pause_turn continuation limit exceeded after "
                  + MAX_PAUSE_CONTINUATIONS
                  + " resumes",
              null);
        }
        mergedSoFar = merged;
        continuations++;
        current.close();
        try {
          current = openStream(buildContinuationRequest(baseRequest, merged));
        } catch (AnthropicException e) {
          return new StreamEvent.Error(e.getMessage(), e);
        } catch (IOException e) {
          return new StreamEvent.Error("Failed to reopen paused stream", e);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return new StreamEvent.Error("Request interrupted", e);
        }
      }
    }

    @Override
    public void close() {
      current.close();
    }
  }

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
    return drainWithContinuation(request, this::openStream);
  }

  /**
   * Provider seam for {@link #drainWithContinuation}: opens one SSE stream for a request. In
   * production this is {@link #openStream(MessagesRequest)}; tests substitute canned iterators.
   */
  interface StreamOpener {
    StreamingIterator open(MessagesRequest request) throws IOException, InterruptedException;
  }

  /**
   * Drain a turn to completion, automatically continuing across {@code pause_turn} boundaries.
   * Anthropic pauses long server-tool turns (web search / web fetch); the resume protocol is to
   * re-send the conversation with the paused assistant content appended <b>unchanged</b>. Segments
   * merge into one logical {@link Response} — text concatenated, tool calls and citations
   * accumulated, usage summed, raw content arrays joined — so callers never observe the pause.
   * Bounded by {@link #MAX_PAUSE_CONTINUATIONS}; exceeding it throws {@link AnthropicException}.
   */
  Response<Void> drainWithContinuation(MessagesRequest request, StreamOpener opener) {
    Response<Void> merged = null;
    var current = request;
    for (var attempt = 0; attempt <= MAX_PAUSE_CONTINUATIONS; attempt++) {
      Response<Void> segment;
      try (var iterator = opener.open(current)) {
        segment = drainToResponse(iterator);
      } catch (AnthropicException | TransientStreamException e) {
        throw e;
      } catch (IOException e) {
        throw new TransientStreamException(
            "Failed to communicate with Anthropic API", e, PROVIDER_NAME);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AnthropicException("Request interrupted", e);
      }
      merged = merged == null ? segment : mergeSegments(merged, segment);
      if (!PAUSE_TURN.equals(merged.metadata().get(STOP_REASON_KEY))) {
        return merged;
      }
      current = buildContinuationRequest(request, merged);
    }
    throw new AnthropicException(
        "pause_turn continuation limit exceeded after "
            + MAX_PAUSE_CONTINUATIONS
            + " resumes; the server-tool loop did not converge");
  }

  /**
   * The continuation request for a paused turn: the original request with the merged-so-far
   * assistant content appended verbatim from {@link #RAW_CONTENT_KEY}.
   */
  @SuppressWarnings("unchecked")
  private MessagesRequest buildContinuationRequest(MessagesRequest base, Response<Void> merged) {
    var rawContent = merged.metadata().get(RAW_CONTENT_KEY);
    if (rawContent == null || rawContent.isEmpty()) {
      throw new AnthropicException(
          "pause_turn received without capturable assistant content; cannot resume");
    }
    List<Object> blocks;
    try {
      blocks = (List<Object>) objectMapper.readValue(rawContent, List.class);
    } catch (Exception e) {
      throw new AnthropicException("Failed to decode paused assistant content for resume", e);
    }
    var messages = new ArrayList<>(base.messages());
    messages.add(new MessagesRequest.MessageEntry("assistant", blocks));
    return base.withMessages(messages);
  }

  /**
   * Merge two segments of one logical assistant turn split by {@code pause_turn}. Later-segment
   * scalars (finish reason, raw stop reason) win; accumulative fields (text, tool calls, citations,
   * thinking, usage, raw content) combine in order.
   */
  private Response<Void> mergeSegments(Response<Void> first, Response<Void> second) {
    var content =
        (first.content() == null ? "" : first.content())
            + (second.content() == null ? "" : second.content());

    var toolCalls = new ArrayList<ToolCall>(first.toolCalls());
    toolCalls.addAll(second.toolCalls());

    var citations = new ArrayList<ai.singlr.core.model.Citation>();
    if (first.citations() != null) {
      citations.addAll(first.citations());
    }
    if (second.citations() != null) {
      citations.addAll(second.citations());
    }

    String thinking;
    if (first.thinking() == null) {
      thinking = second.thinking();
    } else if (second.thinking() == null) {
      thinking = first.thinking();
    } else {
      thinking = first.thinking() + "\n\n" + second.thinking();
    }

    Response.Usage usage;
    if (first.usage() == null) {
      usage = second.usage();
    } else if (second.usage() == null) {
      usage = first.usage();
    } else {
      usage = first.usage().plus(second.usage());
    }

    var metadata = new HashMap<String, String>(second.metadata());
    var mergedRaw =
        mergeRawContent(first.metadata().get(RAW_CONTENT_KEY), metadata.get(RAW_CONTENT_KEY));
    if (mergedRaw != null) {
      metadata.put(RAW_CONTENT_KEY, mergedRaw);
    }

    var finishReason = second.finishReason();
    if (!toolCalls.isEmpty() && finishReason != FinishReason.TOOL_CALLS) {
      finishReason = FinishReason.TOOL_CALLS;
    }

    return Response.newBuilder()
        .withContent(content)
        .withToolCalls(toolCalls)
        .withFinishReason(finishReason)
        .withUsage(usage)
        .withThinking(thinking)
        .withCitations(citations)
        .withMetadata(Map.copyOf(metadata))
        .build();
  }

  @SuppressWarnings("unchecked")
  private String mergeRawContent(String firstRaw, String secondRaw) {
    if (firstRaw == null || firstRaw.isEmpty()) {
      return secondRaw;
    }
    if (secondRaw == null || secondRaw.isEmpty()) {
      return firstRaw;
    }
    try {
      var combined = new ArrayList<Object>(objectMapper.readValue(firstRaw, List.class));
      combined.addAll(objectMapper.readValue(secondRaw, List.class));
      return objectMapper.writeValueAsString(combined);
    } catch (Exception e) {
      throw new AnthropicException("Failed to merge paused-turn content arrays", e);
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
          new ArrayList<>(
              tools.stream()
                  .map(
                      t ->
                          new ToolDefinition(t.name(), t.description(), t.parametersAsJsonSchema()))
                  .toList());
    }
    if (config.webSearch() || config.webFetch()) {
      if (toolDefs == null) {
        toolDefs = new ArrayList<>();
      }
      if (config.webSearch()) {
        toolDefs.add(ToolDefinition.webSearch());
      }
      if (config.webFetch()) {
        toolDefs.add(ToolDefinition.webFetch());
      }
    }

    var toolChoiceConfig = buildToolChoice(tools);
    var thinkingSpec = buildThinkingSpec();

    int maxTokens = config.maxOutputTokens() != null ? config.maxOutputTokens() : maxOutputTokens();
    if (thinkingSpec.thinking() != null && thinkingSpec.thinking().budgetTokens() != null) {
      maxTokens = Math.max(maxTokens, thinkingSpec.thinking().budgetTokens() + 1024);
    }

    // Adaptive-family models (Opus 4.7+, Sonnet 5, Fable 5) reject temperature/top_p outright
    // with a 400, thinking on or off — never send them. Legacy models accept sampling params but
    // reject temperature alongside an active thinking config.
    Double temperature = config.temperature();
    Double topP = config.topP();
    if (thinkingShape != AnthropicModelId.ThinkingShape.LEGACY_BUDGET) {
      temperature = null;
      topP = null;
    } else if (thinkingSpec.thinking() != null
        && !"disabled".equals(thinkingSpec.thinking().type())) {
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
            .withTopP(topP)
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
      if (!(raw.getLast() instanceof ContentBlock)) {
        // Raw-echo content (server-tool turns) must go back verbatim — never annotate it.
        return;
      }
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

  @SuppressWarnings("unchecked")
  static MessagesRequest.MessageEntry convertAssistantMessage(Message message) {
    var rawContent = message.metadata() != null ? message.metadata().get(RAW_CONTENT_KEY) : null;
    if (rawContent != null && !rawContent.isEmpty()) {
      try {
        var blocks = (List<Object>) JsonMapper.builder().build().readValue(rawContent, List.class);
        return new MessagesRequest.MessageEntry("assistant", blocks);
      } catch (Exception ignored) {
        // Undecodable raw content falls back to the reconstructed shape below; the API may reject
        // the echo, but that is a louder failure than silently dropping the whole message.
      }
    }
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
   * Translate {@link ThinkingLevel} into the Anthropic API request shape, dispatching by {@link
   * AnthropicModelId.ThinkingShape}. Adaptive-family models use {@code thinking.type=adaptive} +
   * {@code output_config.effort=...} — except Fable 5 ({@code ALWAYS_ON}), which rejects any
   * explicit thinking config, so the field is omitted and only the effort sibling rides. {@code
   * ThinkingLevel.NONE} omits the field on shapes where omission means "off", sends an explicit
   * {@code disabled} on Sonnet 5 (where omission means adaptive-on), and omits on Fable 5 (which
   * always thinks — there is no off). Legacy Opus 4.6 / Sonnet 4.6 use {@code
   * thinking.type=enabled} + {@code budget_tokens=...}.
   *
   * @return both the {@link ThinkingConfig} and any sibling {@link OutputConfig} that must ride on
   *     the request; either may be {@code null}
   */
  private ThinkingSpec buildThinkingSpec() {
    if (config.thinkingLevel() == null || config.thinkingLevel() == ThinkingLevel.NONE) {
      return thinkingShape == AnthropicModelId.ThinkingShape.ADAPTIVE_DEFAULT_ON
          ? new ThinkingSpec(ThinkingConfig.disabled(), null)
          : new ThinkingSpec(null, null);
    }

    if (thinkingShape != AnthropicModelId.ThinkingShape.LEGACY_BUDGET) {
      var effort =
          switch (config.thinkingLevel()) {
            case NONE -> null;
            case MINIMAL, LOW -> OutputConfig.LOW;
            case MEDIUM -> OutputConfig.MEDIUM;
            case HIGH -> OutputConfig.HIGH;
            case XHIGH -> OutputConfig.XHIGH;
            case MAX -> OutputConfig.MAX;
          };
      var thinking =
          thinkingShape == AnthropicModelId.ThinkingShape.ALWAYS_ON
              ? null
              : ThinkingConfig.adaptive();
      return new ThinkingSpec(thinking, effort);
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
      case "max_tokens", "model_context_window_exceeded" -> FinishReason.LENGTH;
      case "refusal" -> FinishReason.REFUSAL;
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
    private final TreeMap<Integer, StringBuilder> textAccumulators = new TreeMap<>();
    private final TreeMap<Integer, List<Map<String, Object>>> citationAccumulators =
        new TreeMap<>();
    private final TreeMap<Integer, Map<String, Object>> serverBlocks = new TreeMap<>();
    private final Map<Integer, StringBuilder> serverToolInputAccumulators = new HashMap<>();
    private final TreeMap<Integer, ToolCall> completedClientToolBlocks = new TreeMap<>();
    private final List<Citation> citations = new ArrayList<>();
    private boolean sawServerToolBlocks = false;
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
          var block = event.contentBlock();
          var index = event.index();
          if (block != null && index != null) {
            if (block.hasTypeToolUse()) {
              toolCallAccumulators.put(
                  index, new ToolCallAccumulator(block.id(), block.name(), new StringBuilder()));
            } else if (block.hasTypeThinking()) {
              thinkingAccumulators.put(
                  index, new ThinkingAccumulator(new StringBuilder(), new StringBuilder()));
            } else if (block.hasTypeText()) {
              textAccumulators.put(
                  index, new StringBuilder(block.text() == null ? "" : block.text()));
            } else if ("server_tool_use".equals(block.type())) {
              sawServerToolBlocks = true;
              var raw = new LinkedHashMap<String, Object>();
              raw.put("type", "server_tool_use");
              raw.put("id", block.id());
              raw.put("name", block.name());
              serverBlocks.put(index, raw);
              serverToolInputAccumulators.put(index, new StringBuilder());
            } else if ("web_search_tool_result".equals(block.type())
                || "web_fetch_tool_result".equals(block.type())) {
              sawServerToolBlocks = true;
              captureRawServerBlock(index, json);
            }
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
        if (index != null) {
          textAccumulators.computeIfAbsent(index, i -> new StringBuilder()).append(delta.text());
        }
        return new StreamEvent.TextDelta(delta.text());
      }

      if (delta.hasTypeInputJsonDelta() && delta.partialJson() != null && index != null) {
        var serverInput = serverToolInputAccumulators.get(index);
        if (serverInput != null) {
          serverInput.append(delta.partialJson());
          return null;
        }
        var accumulator = toolCallAccumulators.get(index);
        if (accumulator != null) {
          accumulator.jsonBuilder().append(delta.partialJson());
        }
        return null;
      }

      if (delta.hasTypeCitationsDelta() && delta.citation() != null && index != null) {
        citationAccumulators.computeIfAbsent(index, i -> new ArrayList<>()).add(delta.citation());
        harvestCitation(delta.citation());
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
      var serverInput = serverToolInputAccumulators.remove(index);
      if (serverInput != null) {
        var jsonStr = serverInput.toString();
        Map<String, Object> input = Map.of();
        if (!jsonStr.isEmpty()) {
          try {
            input = objectMapper.readValue(jsonStr, Map.class);
          } catch (Exception e) {
            input = Map.of("_raw", jsonStr);
          }
        }
        var raw = serverBlocks.get(index);
        if (raw != null) {
          raw.put("input", input);
        }
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
      completedClientToolBlocks.put(index, tc);
      return new StreamEvent.ToolCallComplete(tc);
    }

    /**
     * Re-parse the raw SSE data line generically and capture the {@code content_block} node
     * verbatim. The typed {@link ContentBlock} record silently drops fields it does not model (e.g.
     * {@code encrypted_content}), which would corrupt the mandatory verbatim echo of server-tool
     * result blocks on later turns.
     */
    @SuppressWarnings("unchecked")
    private void captureRawServerBlock(Integer index, String json) {
      try {
        var eventMap = (Map<String, Object>) objectMapper.readValue(json, Map.class);
        var blockMap = (Map<String, Object>) eventMap.get("content_block");
        if (blockMap != null) {
          serverBlocks.put(index, blockMap);
        }
      } catch (Exception ignored) {
        // Capture failure leaves the block out of RAW_CONTENT; a later echo or pause resume then
        // fails loudly at the API rather than silently sending corrupted content.
      }
    }

    private void harvestCitation(Map<String, Object> citation) {
      var url = citation.get("url");
      var title = citation.get("title");
      var citedText = citation.get("cited_text");
      if (url == null && citedText == null) {
        return;
      }
      citations.add(
          Citation.newBuilder()
              .withSourceId(url != null ? url.toString() : null)
              .withTitle(title != null ? title.toString() : null)
              .withContent(citedText != null ? citedText.toString() : null)
              .build());
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
      if (stopReason != null) {
        metadata.put(STOP_REASON_KEY, stopReason);
      }
      if (sawServerToolBlocks) {
        var rawContent = assembleRawContent();
        if (rawContent != null) {
          metadata.put(RAW_CONTENT_KEY, rawContent);
        }
      }

      var response =
          Response.newBuilder()
              .withContent(content)
              .withToolCalls(calls)
              .withFinishReason(finishReason)
              .withUsage(usage)
              .withThinking(thinking)
              .withCitations(citations.isEmpty() ? List.of() : List.copyOf(citations))
              .withMetadata(metadata.isEmpty() ? Map.of() : Map.copyOf(metadata))
              .build();

      return new StreamEvent.Done(response);
    }

    /**
     * Rebuild the assistant turn's content-block array in original stream-index order for the
     * verbatim echo the API requires after server-tool turns. Server blocks are the raw captured
     * JSON; text, thinking, and client tool_use blocks are reconstructed from their accumulators.
     * Returns {@code null} when serialization fails — the metadata key is then absent and a later
     * echo or resume fails loudly at the API instead of sending corrupted content.
     */
    private String assembleRawContent() {
      var indices = new TreeSet<Integer>();
      indices.addAll(textAccumulators.keySet());
      indices.addAll(thinkingAccumulators.keySet());
      indices.addAll(serverBlocks.keySet());
      indices.addAll(completedClientToolBlocks.keySet());

      var blocks = new ArrayList<Map<String, Object>>();
      for (var index : indices) {
        var server = serverBlocks.get(index);
        if (server != null) {
          blocks.add(server);
          continue;
        }
        var text = textAccumulators.get(index);
        if (text != null) {
          if (text.isEmpty()) {
            continue;
          }
          var block = new LinkedHashMap<String, Object>();
          block.put("type", "text");
          block.put("text", text.toString());
          var blockCitations = citationAccumulators.get(index);
          if (blockCitations != null && !blockCitations.isEmpty()) {
            block.put("citations", blockCitations);
          }
          blocks.add(block);
          continue;
        }
        var thinkingAcc = thinkingAccumulators.get(index);
        if (thinkingAcc != null) {
          var signature = thinkingAcc.signature().toString();
          if (signature.isEmpty()) {
            continue;
          }
          var block = new LinkedHashMap<String, Object>();
          block.put("type", "thinking");
          block.put("thinking", thinkingAcc.text().toString());
          block.put("signature", signature);
          blocks.add(block);
          continue;
        }
        var toolCall = completedClientToolBlocks.get(index);
        if (toolCall != null) {
          var block = new LinkedHashMap<String, Object>();
          block.put("type", "tool_use");
          block.put("id", toolCall.id());
          block.put("name", toolCall.name());
          block.put("input", toolCall.arguments());
          blocks.add(block);
        }
      }
      try {
        return objectMapper.writeValueAsString(blocks);
      } catch (Exception e) {
        return null;
      }
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
