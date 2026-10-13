/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.model.FinishReason;
import org.junit.jupiter.api.Test;

class OpenAIResponseAssemblerTest {

  @Test
  void aCompletedResponseRefusedCallingToolsOrStopped() {
    assertEquals(FinishReason.REFUSAL, OpenAIResponseAssembler.completedReason(true, true));
    assertEquals(FinishReason.TOOL_CALLS, OpenAIResponseAssembler.completedReason(false, true));
    assertEquals(FinishReason.STOP, OpenAIResponseAssembler.completedReason(false, false));
  }

  @Test
  void anIncompleteResponseIsFilteredOnlyByTheContentFilter() {
    assertEquals(
        FinishReason.CONTENT_FILTER, OpenAIResponseAssembler.incompleteReason("content_filter"));
    assertEquals(
        FinishReason.LENGTH, OpenAIResponseAssembler.incompleteReason("max_output_tokens"));
    assertEquals(FinishReason.LENGTH, OpenAIResponseAssembler.incompleteReason("max_messages"));
    assertEquals(FinishReason.LENGTH, OpenAIResponseAssembler.incompleteReason(null));
  }
}
