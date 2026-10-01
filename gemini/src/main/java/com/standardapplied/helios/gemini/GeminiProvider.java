/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.ModelProvider;

/**
 * ModelProvider implementation for Google's Gemini API.
 *
 * <p>Supports Gemini 3 models through the Interactions API.
 */
public class GeminiProvider implements ModelProvider {

  private static final String PROVIDER_NAME = "gemini";

  @Override
  public String name() {
    return PROVIDER_NAME;
  }

  @Override
  public GeminiModel create(String modelId, ModelConfig config) {
    var geminiModel = GeminiModelId.fromId(modelId);
    if (geminiModel == null) {
      throw new IllegalArgumentException("Unsupported model: " + modelId);
    }
    return new GeminiModel(geminiModel, config);
  }

  @Override
  public boolean supports(String modelId) {
    return GeminiModelId.isSupported(modelId);
  }
}
