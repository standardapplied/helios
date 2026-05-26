/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.ModelProvider;

/**
 * ModelProvider implementation for Anthropic's Messages API.
 *
 * <p>Supports canonical Claude models (Opus 4.7, Opus 4.6, Sonnet 4.6) out of the box. When {@link
 * ModelConfig#baseUrl()} is set — pointing at Bedrock, Vertex AI, or a compatible proxy — any
 * non-blank {@code modelId} is accepted and passed verbatim as the {@code model} field in the
 * request body. Context-window and max-output-tokens metadata default to {@code 0} ("unknown") for
 * unrecognised ids; callers can override output tokens via {@link
 * ModelConfig.Builder#withMaxOutputTokens(Integer)}.
 */
public class AnthropicProvider implements ModelProvider {

  private static final String PROVIDER_NAME = "anthropic";

  @Override
  public String name() {
    return PROVIDER_NAME;
  }

  @Override
  public Model create(String modelId, ModelConfig config) {
    var known = AnthropicModelId.fromId(modelId);
    if (known != null) {
      return new AnthropicModel(known, config);
    }
    if (!Strings.isBlank(config.baseUrl())) {
      return new AnthropicModel(modelId, config);
    }
    throw new IllegalArgumentException(
        "Unsupported model: "
            + modelId
            + ". Set ModelConfig.baseUrl for custom endpoints (Bedrock, Vertex, proxy).");
  }

  @Override
  public boolean supports(String modelId) {
    return AnthropicModelId.isSupported(modelId);
  }
}
