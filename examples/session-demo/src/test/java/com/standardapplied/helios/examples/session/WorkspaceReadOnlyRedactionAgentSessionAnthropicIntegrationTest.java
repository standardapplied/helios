/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import com.standardapplied.helios.anthropic.AnthropicModelId;
import com.standardapplied.helios.anthropic.AnthropicProvider;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * {@link WorkspaceReadOnlyRedactionContract} on Claude Sonnet 4.6. Guarded by {@code
 * ANTHROPIC_API_KEY} so the suite stays runnable offline.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
final class WorkspaceReadOnlyRedactionAgentSessionAnthropicIntegrationTest
    extends WorkspaceReadOnlyRedactionContract {

  @Override
  protected Model createModel() {
    var config = ModelConfig.newBuilder().withApiKey(System.getenv("ANTHROPIC_API_KEY")).build();
    return new AnthropicProvider().create(AnthropicModelId.CLAUDE_SONNET_4_6.id(), config);
  }
}
