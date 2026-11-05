/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Response;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Gemini's recitation filter against the real Interactions API: a prompt it blocked mid-response in
 * most calls on 2026-10-20 returns a response every time, never a retried early end, and a blocked
 * one finishes {@link FinishReason#CONTENT_FILTER} with Gemini's message. Whether the filter fires
 * is Google's choice, so a run in which it never does is skipped; {@code
 * GeminiStreamTranscriptTest}'s {@code recitation} fixture covers the blocked stream on every
 * build.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiRecitationIntegrationTest {

  private static final int CALLS = 3;
  private static final Set<FinishReason> ENDINGS =
      Set.of(FinishReason.STOP, FinishReason.CONTENT_FILTER);

  @Test
  void aResponseTheRecitationFilterBlocksFinishesAsAContentFilterWithGeminisMessage(
      TestReporter reporter) {
    var messages =
        List.of(Message.user("Write the numbers one to forty as English words, one per line."));
    var config = ModelConfig.newBuilder().withApiKey(System.getenv("GEMINI_API_KEY")).build();

    try (var model = new GeminiProvider().create(GeminiModelId.GEMINI_3_8_FLASH.id(), config)) {
      for (var call = 1; call <= CALLS; call++) {
        var response = model.chat(messages, List.of());
        reporter.publishEntry("call " + call, response.finishReason().name());
        assertTrue(
            ENDINGS.contains(response.finishReason()),
            "call " + call + " finished " + response.finishReason());
        if (response.finishReason() == FinishReason.CONTENT_FILTER) {
          var explanation = response.metadata().get(Response.REFUSAL_EXPLANATION_KEY);
          assertTrue(explanation.contains("recitation"), explanation);
          return;
        }
      }
    }
    Assumptions.abort(
        "Gemini's recitation filter did not fire in 3 calls on "
            + GeminiModelId.GEMINI_3_8_FLASH.id()
            + "; GeminiStreamTranscriptTest's recitation fixture covers the blocked stream");
  }
}
