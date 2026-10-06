/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.anthropic.api.MessagesRequest;
import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.ChatExchange;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.tool.Tool;
import java.net.http.HttpClient;
import java.util.List;

/**
 * Anthropic Claude model implementation using the Messages API.
 *
 * <p>All requests use SSE streaming internally for robust timeout handling. Synchronous {@link
 * #chat} methods stream under the hood and accumulate the response, avoiding HTTP read timeouts on
 * long-running generations. A per-line idle timeout detects stalled streams and throws a retryable
 * {@link AnthropicException}.
 */
public class AnthropicModel implements Model {

  static final String PROVIDER_NAME = "anthropic";
  static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1/messages";

  /**
   * Output-token ceiling assumed for a Claude model ID this build does not recognise (one not in
   * {@link AnthropicModelId}). Matches the current Opus ceiling — a sane non-zero default so an
   * unrecognised model's requests aren't rejected for {@code max_tokens=0}. Callers override via
   * {@link ModelConfig.Builder#withMaxOutputTokens(Integer)}.
   */
  static final int DEFAULT_MAX_OUTPUT_TOKENS = 32_000;

  /**
   * Metadata key carrying every thinking block in the message as a JSON array of {@code
   * [{"text":"…","signature":"…"}, …]}, one entry per block whatever their number. Each block keeps
   * its own signature: the Anthropic API rejects a signature fabricated across blocks.
   */
  static final String THINKING_BLOCKS_KEY = "anthropic.thinkingBlocks";

  /**
   * Metadata key carrying the assistant turn's full content-block array as raw JSON, set whenever
   * the turn must go back exactly as it arrived: it used Anthropic server tools (web search / web
   * fetch), held a {@code redacted_thinking} block, or interleaved thinking with text or tool
   * calls. Those blocks — including each result's {@code encrypted_content} and each thinking
   * block's position — must be echoed back <b>verbatim</b> on later turns or the API rejects the
   * request with a 400; a later request replays this array as the message content when present.
   */
  static final String RAW_CONTENT_KEY = "anthropic.rawContent";

  /** Metadata key carrying the provider's raw {@code stop_reason} string. */
  static final String STOP_REASON_KEY = "anthropic.stopReason";

  private final String wireModelId;
  private final AnthropicModelId knownModel;
  private final ModelConfig config;
  private final CachePolicy cachePolicy;
  private final HttpClient httpClient;
  final AnthropicRequestBuilder requests;
  final AnthropicStreams streams;
  final ChatExchange<MessagesRequest> exchange;
  private final PauseContinuation continuation;

  AnthropicModel(AnthropicModelId modelId, ModelConfig config) {
    this(modelId, config, CachePolicy.shortLived());
  }

  AnthropicModel(AnthropicModelId modelId, ModelConfig config, CachePolicy cachePolicy) {
    this(modelId != null ? modelId.id() : null, modelId, config, cachePolicy);
  }

  AnthropicModel(String wireModelId, ModelConfig config) {
    this(wireModelId, AnthropicModelId.fromWireId(wireModelId), config, CachePolicy.shortLived());
  }

  AnthropicModel(String wireModelId, ModelConfig config, CachePolicy cachePolicy) {
    this(wireModelId, AnthropicModelId.fromWireId(wireModelId), config, cachePolicy);
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
    if (Strings.isBlank(config.baseUrl()) && Strings.isBlank(config.apiKey())) {
      throw new IllegalArgumentException(
          "config with valid apiKey is required (or set baseUrl + auth header)");
    }
    this.requests = new AnthropicRequestBuilder(wireModelId, knownModel, config, cachePolicy);
    this.wireModelId = wireModelId;
    this.knownModel = knownModel;
    this.config = config;
    this.cachePolicy = cachePolicy;
    this.httpClient = HttpClientFactory.create(config);
    this.streams = new AnthropicStreams(config, httpClient);
    this.exchange =
        new ChatExchange<>(
            PROVIDER_NAME,
            "Anthropic API",
            streams::open,
            AnthropicException::new,
            AnthropicJson.STRUCTURED,
            config.rawOutputCapturePolicy());
    this.continuation = new PauseContinuation(exchange, streams::open);
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
    return requests.defaultMaxTokens();
  }

  @Override
  public RawOutputCapturePolicy rawOutputCapturePolicy() {
    return config.rawOutputCapturePolicy();
  }

  @Override
  public void close() {
    HttpClientFactory.shutdownGracefully(httpClient);
  }

  @Override
  public Response<Void> chat(List<Message> messages, List<Tool> tools) {
    return continuation.drain(requests.build(messages, tools, null));
  }

  @Override
  public <T> Response<T> chat(
      List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
    var request = requests.build(messages, tools, outputSchema.schema().toMap());
    return exchange.structured(continuation.drain(request), outputSchema);
  }

  @Override
  public CloseableIterator<StreamEvent> chatStream(List<Message> messages, List<Tool> tools) {
    return exchange.stream(continuation::open, requests.build(messages, tools, null));
  }
}
