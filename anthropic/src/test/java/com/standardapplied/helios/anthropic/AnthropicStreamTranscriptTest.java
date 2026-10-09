/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import com.standardapplied.helios.core.test.ModelIntegrationContract.Person;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
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
 *
 * <p>{@code progress-notes} is the second turn of a live claude-opus-5-5 tool loop under {@code
 * Display.PROGRESS}, recorded 2026-10-09. {@code structured} is a live claude-sonnet-4-6 reply to a
 * structured-output request, recorded 2026-10-09. The other streams are hand-built.
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
        "pause-turn",
        "progress-notes"
      })
  void transcriptMatchesItsGolden(String name) {
    var transcript =
        ModelHarness.transcript(
            "anthropic/streams/" + name + ".sse",
            uri -> modelAt(uri, AnthropicModelId.CLAUDE_OPUS_5_5),
            AnthropicStreamTranscriptTest::canonicalJson);

    Golden.assertMatches("anthropic/streams/" + name + ".txt", transcript);
  }

  @Test
  void aStructuredReplyParsesIntoItsRecord() {
    var parsed = new ArrayList<Person>();

    ModelHarness.exchange(
        List.of(Golden.read("anthropic/streams/structured.sse")),
        uri -> modelAt(uri, AnthropicModelId.CLAUDE_SONNET_4_6),
        model ->
            parsed.add(
                model
                    .chat(
                        List.of(Message.user("Extract the person")), OutputSchema.of(Person.class))
                    .parsed()));

    assertEquals(List.of(new Person("John Smith", 35, "software engineer")), parsed);
  }

  private static Model modelAt(URI uri, AnthropicModelId id) {
    return new AnthropicProvider()
        .create(
            id.id(),
            ModelConfig.newBuilder()
                .withApiKey("test-key")
                .withBaseUrl(uri + "/v1/messages")
                .build());
  }

  private static String canonicalJson(String text) {
    try {
      return SORTED.writeValueAsString(SORTED.readValue(text, Object.class));
    } catch (JacksonException notJson) {
      return text;
    }
  }
}
