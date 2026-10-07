/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.model.FinishReason;
import org.junit.jupiter.api.Test;

class AnthropicResponseAssemblerTest {

  @Test
  void mapStopReasonEndTurn() {
    assertEquals(FinishReason.STOP, AnthropicResponseAssembler.mapStopReason("end_turn"));
  }

  @Test
  void mapStopReasonStopSequence() {
    assertEquals(FinishReason.STOP, AnthropicResponseAssembler.mapStopReason("stop_sequence"));
  }

  @Test
  void mapStopReasonToolUse() {
    assertEquals(FinishReason.TOOL_CALLS, AnthropicResponseAssembler.mapStopReason("tool_use"));
  }

  @Test
  void mapStopReasonMaxTokens() {
    assertEquals(FinishReason.LENGTH, AnthropicResponseAssembler.mapStopReason("max_tokens"));
  }

  @Test
  void mapStopReasonNull() {
    assertEquals(FinishReason.STOP, AnthropicResponseAssembler.mapStopReason(null));
  }

  @Test
  void mapStopReasonUnknown() {
    assertEquals(FinishReason.STOP, AnthropicResponseAssembler.mapStopReason("unknown"));
  }

  @Test
  void mapStopReasonRefusal() {
    assertEquals(FinishReason.REFUSAL, AnthropicResponseAssembler.mapStopReason("refusal"));
  }

  @Test
  void mapStopReasonContextWindowExceeded() {
    assertEquals(
        FinishReason.LENGTH,
        AnthropicResponseAssembler.mapStopReason("model_context_window_exceeded"));
  }
}
