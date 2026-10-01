/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ModelProvider;

/**
 * ModelProvider implementation for OpenAI's Responses API.
 *
 * <p>Supports every {@link OpenAIModelId} out of the box. When {@link ModelConfig#baseUrl()} is set
 * — pointing at Azure OpenAI, an OpenAI-compatible proxy (LiteLLM, vLLM, Ollama), or Vertex AI —
 * any non-blank {@code modelId} is accepted. The string is used verbatim as the {@code model} field
 * in the request body, which Azure OpenAI maps to the deployment name. Context-window and
 * max-output-tokens metadata default to {@code 0} ("unknown") for unrecognised ids; callers can
 * override output tokens via {@link ModelConfig.Builder#withMaxOutputTokens(Integer)}.
 */
public class OpenAIProvider implements ModelProvider {

  private static final String PROVIDER_NAME = "openai";

  @Override
  public String name() {
    return PROVIDER_NAME;
  }

  @Override
  public Model create(String modelId, ModelConfig config) {
    var known = OpenAIModelId.fromId(modelId);
    if (known != null) {
      return new OpenAIModel(known, config);
    }
    if (!Strings.isBlank(config.baseUrl())) {
      return new OpenAIModel(modelId, config);
    }
    throw new IllegalArgumentException(
        "Unsupported model: "
            + modelId
            + ". Set ModelConfig.baseUrl for custom endpoints (Azure, proxy, Vertex).");
  }

  @Override
  public boolean supports(String modelId) {
    return OpenAIModelId.isSupported(modelId);
  }
}
