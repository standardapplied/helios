/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.tool.Tool;
import java.util.List;

/** The prompt stamp a request for a conversation gets, for histories a test builds by hand. */
final class PromptStamps {

  private PromptStamps() {}

  /** The stamp of a request sending {@code messages}' system instruction and {@code tools}. */
  static String of(List<Message> messages, List<Tool> tools) {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    return new GeminiRequestBuilder(GeminiModelId.GEMINI_3_5_FLASH, config)
        .build(messages, tools, null)
        .promptStamp();
  }
}
