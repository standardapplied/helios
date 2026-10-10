/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import com.standardapplied.helios.anthropic.AnthropicModelId;
import com.standardapplied.helios.anthropic.AnthropicProvider;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ToolChoice;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * {@link ReadToolMultimodalContract} on Claude Sonnet 4.6, the cheapest vision/PDF-capable
 * Anthropic model in the supported matrix: the request builder must turn each attachment into a
 * Messages API {@code image} / {@code document} content block. Guarded by {@code ANTHROPIC_API_KEY}
 * so the suite stays runnable offline.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
final class ReadToolMultimodalAnthropicIntegrationTest extends ReadToolMultimodalContract {

  @Override
  protected Model createModel(ToolChoice toolChoice) {
    var config =
        ModelConfig.newBuilder()
            .withApiKey(System.getenv("ANTHROPIC_API_KEY"))
            .withToolChoice(toolChoice)
            .build();
    return new AnthropicProvider().create(AnthropicModelId.CLAUDE_SONNET_4_6.id(), config);
  }
}
