/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import com.standardapplied.helios.core.test.StreamParity;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
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
 * reporting the usage so far. The API documents no refusal terminal; {@code recitation} is a live
 * gemini-3.8-flash turn recorded 2026-10-20 that Gemini's recitation filter stopped mid-response,
 * ending the body with a bare {@code {"error":...}} line and no terminator, kept byte for byte.
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
        "missing-tool-id",
        "recitation");
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

  @Test
  void aRecitationBlockUnderASchemaEndsInOneContentFilterLeftUnparsed() {
    var responses = new ArrayList<Response<Answer>>();

    var requests =
        ModelHarness.exchange(
            List.of(Golden.read("gemini/streams/recitation.sse")),
            GeminiStreamTranscriptTest::modelAt,
            model ->
                responses.add(
                    model.chat(
                        List.of(Message.user("Count")), List.of(), OutputSchema.of(Answer.class))));

    var response = responses.getFirst();
    assertEquals(FinishReason.CONTENT_FILTER, response.finishReason());
    assertNull(response.parsed());
    assertEquals("recitation", response.metadata().get(Response.REFUSAL_CATEGORY_KEY));
    assertEquals(1, requests.size());
  }

  /** The answer the schema asks for. */
  public record Answer(String numbers) {}

  private static Model modelAt(URI uri) {
    return new GeminiProvider()
        .create(
            GeminiModelId.GEMINI_3_8_FLASH.id(),
            ModelConfig.newBuilder().withApiKey("test-key").withBaseUrl(uri + "/v1beta").build());
  }
}
