/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Characterization: for each recorded stream under {@code golden/gemini/streams}, the exact events
 * {@code chatStream} yields and the response {@code chat} assembles.
 */
class GeminiStreamTranscriptTest {

  @ParameterizedTest
  @ValueSource(strings = {"text", "tool-calls", "thinking", "error", "citations", "malformed"})
  void transcriptMatchesItsGolden(String name) {
    var transcript =
        ModelHarness.transcript(
            "gemini/streams/" + name + ".sse",
            uri ->
                new GeminiModel(
                    GeminiModelId.GEMINI_3_5_FLASH,
                    ModelConfig.newBuilder()
                        .withApiKey("test-key")
                        .withBaseUrl(uri + "/v1beta")
                        .build()));

    Golden.assertMatches("gemini/streams/" + name + ".txt", transcript);
  }
}
