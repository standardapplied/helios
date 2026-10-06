/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.RedirectTrap;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class OpenAIRedirectTest {

  private static Model trapped(ModelConfig config) {
    return new OpenAIProvider().create(OpenAIModelId.GPT_4O.id(), config);
  }

  @ParameterizedTest
  @MethodSource(RedirectTrap.SCENARIOS)
  void chatNeverFollowsARedirect(RedirectTrap.Scenario scenario) throws IOException {
    RedirectTrap.assertChatRefused(scenario, OpenAIRedirectTest::trapped, OpenAIException.class);
  }

  @ParameterizedTest
  @MethodSource(RedirectTrap.SCENARIOS)
  void chatStreamNeverFollowsARedirect(RedirectTrap.Scenario scenario) throws IOException {
    RedirectTrap.assertChatStreamRefused(
        scenario, OpenAIRedirectTest::trapped, OpenAIException.class);
  }

  @Test
  void refusedRedirectReleasesItsConnection() throws IOException {
    RedirectTrap.assertRefusalReleasesConnection(
        OpenAIRedirectTest::trapped, OpenAIException.class);
  }
}
