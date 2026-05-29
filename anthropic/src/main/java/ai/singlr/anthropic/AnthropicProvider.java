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
 * <p>Curated Claude models (Opus 4.8, Opus 4.7, Opus 4.6, Sonnet 4.6) are recognised with full
 * metadata. Beyond those, any {@code modelId} starting with {@value
 * AnthropicModelId#CLAUDE_ID_PREFIX} is accepted against the default endpoint and passed verbatim
 * as the {@code model} field, so a newly-released Claude can be used before this provider's enum
 * catches up; unrecognised ids fall back to adaptive thinking and {@link
 * AnthropicModel#DEFAULT_MAX_OUTPUT_TOKENS} output tokens. When {@link ModelConfig#baseUrl()} is
 * set — pointing at Bedrock, Vertex AI, or a compatible proxy — any non-blank {@code modelId} is
 * accepted regardless of prefix. Callers can always override output tokens via {@link
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
    if (AnthropicModelId.hasClaudePrefix(modelId) || !Strings.isBlank(config.baseUrl())) {
      return new AnthropicModel(modelId, config);
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
}
