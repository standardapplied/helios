/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.model.FinishReason;
import org.junit.jupiter.api.Test;

class OpenAIResponseAssemblerTest {

  @Test
  void mapStatusCompleted() {
    assertEquals(FinishReason.STOP, OpenAIResponseAssembler.mapStatus("completed"));
  }

  @Test
  void mapStatusIncomplete() {
    assertEquals(FinishReason.LENGTH, OpenAIResponseAssembler.mapStatus("incomplete"));
  }

  @Test
  void mapStatusFailed() {
    assertEquals(FinishReason.ERROR, OpenAIResponseAssembler.mapStatus("failed"));
  }

  @Test
  void mapStatusNull() {
    assertEquals(FinishReason.STOP, OpenAIResponseAssembler.mapStatus(null));
  }

  @Test
  void mapStatusUnknown() {
    assertEquals(FinishReason.STOP, OpenAIResponseAssembler.mapStatus("unknown"));
  }
}
