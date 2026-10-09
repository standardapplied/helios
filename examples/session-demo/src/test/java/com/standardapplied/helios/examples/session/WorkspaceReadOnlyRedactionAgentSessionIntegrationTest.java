/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.gemini.GeminiModelId;
import com.standardapplied.helios.gemini.GeminiProvider;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * {@link WorkspaceReadOnlyRedactionContract} on Gemini 3.5 Flash. Guarded by {@code GEMINI_API_KEY}
 * so the suite stays runnable offline.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
final class WorkspaceReadOnlyRedactionAgentSessionIntegrationTest
    extends WorkspaceReadOnlyRedactionContract {

  @Override
  protected Model createModel(ToolChoice toolChoice) {
    var config =
        ModelConfig.newBuilder()
            .withApiKey(System.getenv("GEMINI_API_KEY"))
            .withToolChoice(toolChoice)
            .build();
    return new GeminiProvider().create(GeminiModelId.GEMINI_3_5_FLASH.id(), config);
  }
}
