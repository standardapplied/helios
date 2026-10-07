/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.gemini.GeminiModelId;
import com.standardapplied.helios.gemini.GeminiProvider;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * {@link ReadToolMultimodalContract} on Gemini 3.5 Flash: the provider adapter must turn each
 * attachment into {@code inline_data}. Guarded by {@code GEMINI_API_KEY} so the suite stays
 * runnable offline.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
final class ReadToolMultimodalThroughLoopIntegrationTest extends ReadToolMultimodalContract {

  @Override
  protected Model createModel() {
    var config = ModelConfig.newBuilder().withApiKey(System.getenv("GEMINI_API_KEY")).build();
    return new GeminiProvider().create(GeminiModelId.GEMINI_3_5_FLASH.id(), config);
  }
}
