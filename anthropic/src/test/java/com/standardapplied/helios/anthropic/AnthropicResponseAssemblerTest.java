/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.standardapplied.helios.core.model.FinishReason;
import org.junit.jupiter.api.Test;

class AnthropicResponseAssemblerTest {

  @Test
  void finishReasonOfEndTurn() {
    assertEquals(FinishReason.STOP, AnthropicResponseAssembler.finishReason("end_turn"));
  }

  @Test
  void finishReasonOfStopSequence() {
    assertEquals(FinishReason.STOP, AnthropicResponseAssembler.finishReason("stop_sequence"));
  }

  @Test
  void finishReasonOfToolUse() {
    assertEquals(FinishReason.TOOL_CALLS, AnthropicResponseAssembler.finishReason("tool_use"));
  }

  @Test
  void finishReasonOfMaxTokens() {
    assertEquals(FinishReason.LENGTH, AnthropicResponseAssembler.finishReason("max_tokens"));
  }

  @Test
  void finishReasonOfNull() {
    assertNull(AnthropicResponseAssembler.finishReason(null));
  }

  @Test
  void finishReasonOfUnknown() {
    assertNull(AnthropicResponseAssembler.finishReason("unknown"));
  }

  @Test
  void finishReasonOfRefusal() {
    assertEquals(FinishReason.REFUSAL, AnthropicResponseAssembler.finishReason("refusal"));
  }

  @Test
  void finishReasonOfContextWindowExceeded() {
    assertEquals(
        FinishReason.LENGTH,
        AnthropicResponseAssembler.finishReason("model_context_window_exceeded"));
  }

  @Test
  void finishReasonOfPauseTurn() {
    assertEquals(FinishReason.STOP, AnthropicResponseAssembler.finishReason("pause_turn"));
  }
}
