/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import com.standardapplied.helios.openai.OpenAIAzureIntegrationTest.ResponseWithMaps;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Characterization: for each recorded stream under {@code golden/openai/streams}, the exact events
 * {@code chatStream} yields and the response {@code chat} assembles.
 *
 * <p>{@code open-map-structured} is built by hand from the documented Responses API stream shape
 * for a gpt-4o reply to a non-strict {@code json_schema} request, 2026-10-09: no OpenAI key was
 * available to record it live. The other streams are hand-built.
 */
class OpenAIStreamTranscriptTest {

  @ParameterizedTest
  @ValueSource(strings = {"text", "tool-calls", "thinking", "error", "failed", "malformed"})
  void transcriptMatchesItsGolden(String name) {
    var transcript =
        ModelHarness.transcript(
            "openai/streams/" + name + ".sse", uri -> modelAt(uri, OpenAIModelId.GPT_5_6.id()));

    Golden.assertMatches("openai/streams/" + name + ".txt", transcript);
  }

  @Test
  void anOpenMapStructuredReplyParsesIntoItsRecord() {
    var parsed = new ArrayList<ResponseWithMaps>();

    var requests =
        ModelHarness.exchange(
            List.of(Golden.read("openai/streams/open-map-structured.sse")),
            uri -> modelAt(uri, "gpt-4o-deployment"),
            model ->
                parsed.add(
                    model
                        .chat(
                            List.of(Message.user("List 2 colors.")),
                            List.of(),
                            OutputSchema.of(ResponseWithMaps.class))
                        .parsed()));

    assertEquals(
        List.of(
            new ResponseWithMaps(
                List.of("red", "blue"),
                "colors",
                Map.of("red", "warm"),
                Map.of("blue", List.of("sky")))),
        parsed);
    var format =
        (Map<?, ?>)
            ((Map<?, ?>)
                    JsonMapper.builder()
                        .build()
                        .readValue(requests.getFirst().body(), Map.class)
                        .get("text"))
                .get("format");
    assertEquals(false, format.get("strict"));
  }

  private static Model modelAt(URI uri, String modelId) {
    return new OpenAIProvider()
        .create(
            modelId,
            ModelConfig.newBuilder()
                .withApiKey("test-key")
                .withBaseUrl(uri + "/v1/responses")
                .build());
  }
}
