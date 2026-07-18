/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import ai.singlr.anthropic.api.CacheControl;
import ai.singlr.anthropic.api.ContentBlock;
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
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    this(wireModelId, AnthropicModelId.fromWireId(wireModelId), config, CachePolicy.shortLived());
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
    private AnthropicStreamingIterator current;
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

  private AnthropicStreamingIterator openStream(MessagesRequest request)
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
    return new AnthropicStreamingIterator(httpResponse, objectMapper, config.streamIdleTimeout());
  }

  /**
   * Drive {@link #openStream(MessagesRequest)} and {@link
   * #drainToResponse(AnthropicStreamingIterator)}, promoting transport-layer failures to the typed
   * signals the session loop's retry policy understands.
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
    AnthropicStreamingIterator open(MessagesRequest request)
        throws IOException, InterruptedException;
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
    return base.continuationWith(messages);
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
    var mergedRaw = mergeRawContent(first, second);
    if (mergedRaw != null) {
      metadata.put(RAW_CONTENT_KEY, mergedRaw);
    }
    mergeThinkingMetadata(first.metadata(), metadata);

    // Only promote a STOP into TOOL_CALLS — the defensive override for streams whose final stop
    // reason lags behind an emitted tool_use block. REFUSAL / LENGTH / ERROR from the final
    // segment must survive the merge so the session loop routes them correctly.
    var finishReason = second.finishReason();
    if (!toolCalls.isEmpty() && finishReason == FinishReason.STOP) {
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

  /**
   * Merge the two segments' content-block arrays for the verbatim echo. A segment without {@link
   * #RAW_CONTENT_KEY} (e.g. a text-only continuation that used no server tools) is reconstructed
   * from its response — signed thinking blocks, text, and client tool_use — so the merged echo
   * carries the <em>entire</em> assistant turn, not just the paused prefix.
   */
  private String mergeRawContent(Response<Void> first, Response<Void> second) {
    var combined = new ArrayList<Object>(rawBlocksOf(first));
    combined.addAll(rawBlocksOf(second));
    if (combined.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(combined);
    } catch (Exception e) {
      throw new AnthropicException("Failed to merge paused-turn content arrays", e);
    }
  }

  @SuppressWarnings("unchecked")
  private List<Object> rawBlocksOf(Response<Void> segment) {
    var raw = segment.metadata().get(RAW_CONTENT_KEY);
    if (raw != null && !raw.isEmpty()) {
      try {
        return (List<Object>) objectMapper.readValue(raw, List.class);
      } catch (Exception e) {
        throw new AnthropicException("Failed to decode segment content array", e);
      }
    }
    var blocks = new ArrayList<Object>();
    for (var tb : decodeThinkingBlocks(segment.metadata())) {
      var block = new LinkedHashMap<String, Object>();
      block.put("type", "thinking");
      block.put("thinking", tb.text());
      block.put("signature", tb.signature());
      blocks.add(block);
    }
    if (segment.content() != null && !segment.content().isEmpty()) {
      var block = new LinkedHashMap<String, Object>();
      block.put("type", "text");
      block.put("text", segment.content());
      blocks.add(block);
    }
    for (var tc : segment.toolCalls()) {
      var block = new LinkedHashMap<String, Object>();
      block.put("type", "tool_use");
      block.put("id", tc.id());
      block.put("name", tc.name());
      block.put("input", tc.arguments());
      blocks.add(block);
    }
    return blocks;
  }

  /**
   * Fold the first segment's thinking metadata into the merged map (which is seeded from the second
   * segment): {@link #THINKING_BLOCKS_KEY} arrays concatenate in segment order, and the legacy
   * single-block keys survive only when the combined turn has exactly one thinking block.
   */
  @SuppressWarnings("unchecked")
  private void mergeThinkingMetadata(Map<String, String> firstMeta, Map<String, String> merged) {
    var firstBlocks = decodeThinkingBlocks(firstMeta);
    if (firstBlocks.isEmpty()) {
      return;
    }
    var combined = new ArrayList<ThinkingBlock>(firstBlocks);
    combined.addAll(
        decodeThinkingBlocks(merged.containsKey(THINKING_BLOCKS_KEY) ? merged : Map.of()));
    try {
      var arr = new ArrayList<Map<String, String>>(combined.size());
      for (var tb : combined) {
        arr.add(Map.of("text", tb.text(), "signature", tb.signature()));
      }
      merged.put(THINKING_BLOCKS_KEY, objectMapper.writeValueAsString(arr));
    } catch (Exception e) {
      throw new AnthropicException("Failed to merge thinking metadata", e);
    }
    if (combined.size() == 1) {
      merged.put(THINKING_KEY, combined.getFirst().text());
      merged.put(THINKING_SIGNATURE_KEY, combined.getFirst().signature());
    } else {
      merged.remove(THINKING_KEY);
      merged.remove(THINKING_SIGNATURE_KEY);
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
  private Response<Void> drainToResponse(AnthropicStreamingIterator iterator) {
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

    List<ToolDefinition> clientToolDefs = null;
    if (tools != null && !tools.isEmpty()) {
      clientToolDefs =
          tools.stream()
              .map(t -> new ToolDefinition(t.name(), t.description(), t.parametersAsJsonSchema()))
              .toList();
    }
    var serverToolDefs = new ArrayList<ToolDefinition>();
    if (config.webSearch()) {
      serverToolDefs.add(ToolDefinition.webSearch());
    }
    if (config.webFetch()) {
      serverToolDefs.add(ToolDefinition.webFetch());
    }
    if (clientToolDefs != null && !serverToolDefs.isEmpty()) {
      for (var server : serverToolDefs) {
        for (var client : clientToolDefs) {
          if (server.name().equals(client.name())) {
            throw new IllegalArgumentException(
                "Client tool name '"
                    + client.name()
                    + "' collides with the enabled Anthropic server tool of the same name;"
                    + " rename the client tool or disable the toggle");
          }
        }
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
      // The tools breakpoint anchors to the last CLIENT tool; server-tool entries keep the exact
      // documented {type, name} shape and follow after. The system breakpoint still covers the
      // whole tools+system prefix, so nothing is lost when there are no client tools.
      builder.withTools(concatTools(withCachedTail(clientToolDefs, breakpoint), serverToolDefs));
      annotateLastMessageForCaching(apiMessages, breakpoint);
    } else {
      builder.withSystem(systemInstruction);
      builder.withTools(concatTools(clientToolDefs, serverToolDefs));
    }

    return builder.build();
  }

  private static List<ToolDefinition> concatTools(
      List<ToolDefinition> clientTools, List<ToolDefinition> serverTools) {
    if (serverTools == null || serverTools.isEmpty()) {
      return clientTools;
    }
    var combined = new ArrayList<ToolDefinition>();
    if (clientTools != null) {
      combined.addAll(clientTools);
    }
    combined.addAll(serverTools);
    return List.copyOf(combined);
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

  /** Shared mapper for static decode paths — Jackson 3 mappers are immutable and thread-safe. */
  private static final ObjectMapper SHARED_MAPPER = JsonMapper.builder().build();

  @SuppressWarnings("unchecked")
  static MessagesRequest.MessageEntry convertAssistantMessage(Message message) {
    var rawContent = message.metadata() != null ? message.metadata().get(RAW_CONTENT_KEY) : null;
    if (rawContent != null && !rawContent.isEmpty()) {
      try {
        var blocks = (List<Object>) SHARED_MAPPER.readValue(rawContent, List.class);
        return new MessagesRequest.MessageEntry("assistant", blocks);
      } catch (Exception e) {
        throw new AnthropicException(
            "Corrupted server-tool content on assistant message; refusing to echo a truncated"
                + " turn (the API would reject or mis-read it)",
            e);
      }
    }
    var thinkingBlocks =
        decodeThinkingBlocks(message.metadata() == null ? Map.of() : message.metadata());
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
  static List<ThinkingBlock> decodeThinkingBlocks(Map<String, String> metadata) {
    if (metadata == null) {
      return List.of();
    }
    var encoded = metadata.get(THINKING_BLOCKS_KEY);
    if (encoded != null && !encoded.isEmpty()) {
      try {
        @SuppressWarnings("unchecked")
        var raw = (List<Map<String, Object>>) SHARED_MAPPER.readValue(encoded, List.class);
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
    var signature = metadata.get(THINKING_SIGNATURE_KEY);
    if (signature != null && !signature.isEmpty()) {
      var text = metadata.getOrDefault(THINKING_KEY, "");
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
}
