/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.ModelIntegrationContract;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class OpenAIModelIntegrationTest extends ModelIntegrationContract {

  private static Model model;

  OpenAIModelIntegrationTest() {
    super("gpt-4.1-mini", "openai", 1_000_000);
  }

  @BeforeAll
  static void setUp() {
    model = gpt41Mini(ModelConfig.newBuilder());
  }

  private static Model gpt41Mini(ModelConfig.Builder config) {
    return new OpenAIProvider()
        .create(
            OpenAIModelId.GPT_4_1_MINI.id(),
            config.withApiKey(System.getenv("OPENAI_API_KEY")).build());
  }

  @Override
  protected Model model() {
    return model;
  }

  @Override
  protected Model model(ModelConfig.Builder config) {
    return gpt41Mini(config);
  }
}
