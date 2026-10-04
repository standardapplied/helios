/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.RedirectTrap;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class GeminiRedirectTest {

  private static Model trapped(ModelConfig config) {
    return new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
  }

  @ParameterizedTest
  @MethodSource(RedirectTrap.SCENARIOS)
  void chatNeverFollowsARedirect(RedirectTrap.Scenario scenario) throws IOException {
    RedirectTrap.assertChatRefused(scenario, GeminiRedirectTest::trapped, GeminiException.class);
  }

  @ParameterizedTest
  @MethodSource(RedirectTrap.SCENARIOS)
  void chatStreamNeverFollowsARedirect(RedirectTrap.Scenario scenario) throws IOException {
    RedirectTrap.assertChatStreamRefused(
        scenario, GeminiRedirectTest::trapped, GeminiException.class);
  }

  @Test
  void refusedRedirectReleasesItsConnection() throws IOException {
    RedirectTrap.assertRefusalReleasesConnection(
        GeminiRedirectTest::trapped, GeminiException.class);
  }
}
