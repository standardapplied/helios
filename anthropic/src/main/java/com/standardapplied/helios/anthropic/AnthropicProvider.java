/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.anthropic.api.MessagesRequest;
import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ModelProvider;
import com.standardapplied.helios.core.provider.ChatExchange;
import com.standardapplied.helios.core.provider.StreamingModel;

/**
 * ModelProvider implementation for Anthropic's Messages API.
 *
 * <p>Every request streams over SSE, so a long generation never meets an HTTP read timeout; a
 * blocking chat drains the stream, and a per-line idle timeout turns a stalled stream into a
 * retryable failure. A turn the API pauses ({@code pause_turn}) is resumed until it completes.
 *
 * <p>Curated {@link AnthropicModelId} values are recognised with full metadata. Beyond those, any
 * {@code modelId} starting with {@value AnthropicModelId#CLAUDE_ID_PREFIX} is accepted against the
 * default endpoint and passed verbatim as the {@code model} field, so a newly-released Claude can
 * be used before this provider's enum catches up; unrecognised ids fall back to adaptive thinking
 * and {@value AnthropicRequestBuilder#DEFAULT_MAX_OUTPUT_TOKENS} output tokens. When {@link
 * ModelConfig#baseUrl()} is set — pointing at Bedrock, Vertex AI, or a compatible proxy — any
 * non-blank {@code modelId} is accepted regardless of prefix. Callers can always override output
 * tokens via {@link ModelConfig.Builder#withMaxOutputTokens(Integer)}.
 *
 * <p>Managed five-minute prompt caching is enabled by default. Use {@link #create(String,
 * ModelConfig, CachePolicy)} to select the one-hour cache or disable prompt caching.
 */
public class AnthropicProvider implements ModelProvider {

  static final String PROVIDER_NAME = "anthropic";

  @Override
  public String name() {
    return PROVIDER_NAME;
  }

  @Override
  public Model create(String modelId, ModelConfig config) {
    return create(modelId, config, CachePolicy.shortLived());
  }

  /**
   * Create an Anthropic model with an explicit prompt-caching policy.
   *
   * @param modelId the model identifier
   * @param config provider configuration
   * @param cachePolicy short-lived (5m), long-lived (1h), or disabled
   * @return a configured Anthropic model
   * @throws IllegalArgumentException if the model is unsupported or any required argument is
   *     invalid
   */
  public Model create(String modelId, ModelConfig config, CachePolicy cachePolicy) {
    var known = AnthropicModelId.fromId(modelId);
    if (known != null) {
      return model(known.id(), known, config, cachePolicy);
    }
    if (AnthropicModelId.hasClaudePrefix(modelId) || !Strings.isBlank(config.baseUrl())) {
      return model(modelId, AnthropicModelId.fromWireId(modelId), config, cachePolicy);
    }
    throw new IllegalArgumentException(
        "Unsupported model: "
            + modelId
            + ". Use a '"
            + AnthropicModelId.CLAUDE_ID_PREFIX
            + "' model id, or set ModelConfig.baseUrl for custom endpoints (Bedrock, Vertex,"
            + " proxy).");
  }

  @Override
  public boolean supports(String modelId) {
    return AnthropicModelId.isSupported(modelId) || AnthropicModelId.hasClaudePrefix(modelId);
  }

  private static Model model(
      String wireModelId,
      AnthropicModelId knownModel,
      ModelConfig config,
      CachePolicy cachePolicy) {
    validate(wireModelId, config, cachePolicy);
    var requests = new AnthropicRequestBuilder(wireModelId, knownModel, config, cachePolicy);
    var httpClient = HttpClientFactory.create(config);
    var streams = new AnthropicStreams(config, httpClient);
    var exchange =
        new ChatExchange<>(PROVIDER_NAME, "Anthropic API", streams::open, AnthropicException::new);
    return StreamingModel.<MessagesRequest>newBuilder()
        .withId(wireModelId)
        .withProvider(PROVIDER_NAME)
        .withConfig(config)
        .withDefaultContextWindow(knownModel != null ? knownModel.contextWindow() : 0)
        .withMaxOutputTokens(requests.defaultMaxTokens())
        .withHttpClient(httpClient)
        .withRequests(requests)
        .withExchange(new PauseContinuation(exchange, streams::open))
        .withJson(AnthropicJson.STRUCTURED)
        .build();
  }

  private static void validate(String wireModelId, ModelConfig config, CachePolicy cachePolicy) {
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
  }
}
