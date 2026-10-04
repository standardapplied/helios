/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.RedirectTrap;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AnthropicRedirectTest {

  private static Model trapped(ModelConfig config) {
    return new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
  }

  @ParameterizedTest
  @MethodSource(RedirectTrap.SCENARIOS)
  void chatNeverFollowsARedirect(RedirectTrap.Scenario scenario) throws IOException {
    RedirectTrap.assertChatRefused(
        scenario, AnthropicRedirectTest::trapped, AnthropicException.class);
  }

  @ParameterizedTest
  @MethodSource(RedirectTrap.SCENARIOS)
  void chatStreamNeverFollowsARedirect(RedirectTrap.Scenario scenario) throws IOException {
    RedirectTrap.assertChatStreamRefused(
        scenario, AnthropicRedirectTest::trapped, AnthropicException.class);
  }

  @Test
  void refusedRedirectReleasesItsConnection() throws IOException {
    RedirectTrap.assertRefusalReleasesConnection(
        AnthropicRedirectTest::trapped, AnthropicException.class);
  }
}
