/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Characterization: for each recorded stream under {@code golden/openai/streams}, the exact events
 * {@code chatStream} yields and the response {@code chat} assembles.
 */
class OpenAIStreamTranscriptTest {

  @ParameterizedTest
  @ValueSource(strings = {"text", "tool-calls", "thinking", "error", "failed", "malformed"})
  void transcriptMatchesItsGolden(String name) {
    var transcript =
        ModelHarness.transcript(
            "openai/streams/" + name + ".sse",
            uri ->
                new OpenAIModel(
                    OpenAIModelId.GPT_5_6,
                    ModelConfig.newBuilder()
                        .withApiKey("test-key")
                        .withBaseUrl(uri + "/v1/responses")
                        .build()));

    Golden.assertMatches("openai/streams/" + name + ".txt", transcript);
  }
}
