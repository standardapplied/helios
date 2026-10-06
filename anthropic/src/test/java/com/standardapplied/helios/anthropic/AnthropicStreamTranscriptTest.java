/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Characterization: for each recorded stream under {@code golden/anthropic/streams}, the exact
 * events {@code chatStream} yields and the response {@code chat} assembles, including the
 * continuation request a {@code pause_turn} triggers. JSON held in metadata renders with its keys
 * sorted: the thinking-block codec builds it from hash maps, so its key order varies between runs.
 */
class AnthropicStreamTranscriptTest {

  private static final JsonMapper SORTED =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "text",
        "tool-calls",
        "thinking",
        "error",
        "malformed",
        "server-tool",
        "interleaved-thinking",
        "redacted-thinking",
        "refusal",
        "pause-turn"
      })
  void transcriptMatchesItsGolden(String name) {
    var transcript =
        ModelHarness.transcript(
            "anthropic/streams/" + name + ".sse",
            uri ->
                new AnthropicProvider()
                    .create(
                        AnthropicModelId.CLAUDE_OPUS_5_5.id(),
                        ModelConfig.newBuilder()
                            .withApiKey("test-key")
                            .withBaseUrl(uri + "/v1/messages")
                            .build()),
            AnthropicStreamTranscriptTest::canonicalJson);

    Golden.assertMatches("anthropic/streams/" + name + ".txt", transcript);
  }

  private static String canonicalJson(String text) {
    try {
      return SORTED.writeValueAsString(SORTED.readValue(text, Object.class));
    } catch (JacksonException notJson) {
      return text;
    }
  }
}
