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
    var config = ModelConfig.newBuilder().withApiKey(System.getenv("OPENAI_API_KEY")).build();
    model = new OpenAIProvider().create(OpenAIModelId.GPT_4_1_MINI.id(), config);
  }

  @Override
  protected Model model() {
    return model;
  }
}
