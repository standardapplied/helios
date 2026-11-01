/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import com.standardapplied.helios.core.test.StreamParity;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Characterization: for each recorded stream under {@code golden/gemini/streams}, the exact events
 * {@code chatStream} yields and the response {@code chat} assembles.
 *
 * <p>{@code grounded} is a live gemini-3.8-flash Google Search turn with its url citations,
 * recorded 2026-11-01; {@code requires-action} (a function-call turn under tool choice {@code any})
 * and {@code incomplete} (a five-token output ceiling) are live gemini-3.8-flash turns recorded
 * 2026-10-13, and {@code eof-in-thinking} is the live thought-summary turn of that day cut after
 * its thought. The other streams are hand-built from the documented event shapes (Interactions API
 * reference, checked 2026-10-13); the {@code eof-*} and {@code empty} streams are bodies cut short,
 * which end in an error carrying what they produced, {@code eof-after-usage} after a status update
 * reporting the usage so far. The API documents no refusal terminal.
 */
class GeminiStreamTranscriptTest {

  static List<String> streams() {
    return List.of(
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
        "eof-after-usage",
        "eof-in-thinking",
        "incomplete",
        "requires-action",
        "failed",
        "bad-tool-arguments",
        "null-argument",
        "missing-tool-id");
  }

  @ParameterizedTest
  @MethodSource("streams")
  void chunkStreamAgreesWithChat(String name) {
    StreamParity.assertAgrees(
        "gemini/streams/" + name + ".sse", GeminiStreamTranscriptTest::modelAt);
  }

  @ParameterizedTest
  @MethodSource("streams")
  void transcriptMatchesItsGolden(String name) {
    var transcript =
        ModelHarness.transcript(
            "gemini/streams/" + name + ".sse", GeminiStreamTranscriptTest::modelAt);

    Golden.assertMatches("gemini/streams/" + name + ".txt", transcript);
  }

  private static Model modelAt(URI uri) {
    return new GeminiProvider()
        .create(
            GeminiModelId.GEMINI_3_8_FLASH.id(),
            ModelConfig.newBuilder().withApiKey("test-key").withBaseUrl(uri + "/v1beta").build());
  }
}
