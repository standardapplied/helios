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
 *
 * <p>{@code grounded} is a live gemini-3.5-flash Google Search turn with its url citations,
 * recorded 2026-10-09. The other streams are hand-built from the documented event shapes
 * (Interactions API streaming guide, checked 2026-10-13); the {@code eof-*} and {@code empty}
 * streams are bodies cut short, which end in an error carrying what they produced. The API reports
 * usage on {@code interaction.completed}, so no stream is cut after usage, and it documents no
 * refusal terminal.
 */
class GeminiStreamTranscriptTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "text",
        "tool-calls",
        "thinking",
        "error",
        "citations",
        "malformed",
        "grounded",
        "empty",
        "eof-after-text",
        "eof-in-tool-arguments",
        "eof-mid-event",
        "incomplete",
        "failed",
        "bad-tool-arguments",
        "null-argument"
      })
  void transcriptMatchesItsGolden(String name) {
    var transcript =
        ModelHarness.transcript(
            "gemini/streams/" + name + ".sse",
            uri ->
                new GeminiProvider()
                    .create(
                        GeminiModelId.GEMINI_3_5_FLASH.id(),
                        ModelConfig.newBuilder()
                            .withApiKey("test-key")
                            .withBaseUrl(uri + "/v1beta")
                            .build()));

    Golden.assertMatches("gemini/streams/" + name + ".txt", transcript);
  }
}
