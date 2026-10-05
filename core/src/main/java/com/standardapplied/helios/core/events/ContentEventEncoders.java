/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.events;

import com.standardapplied.helios.core.events.HeliosEvent.AssistantText;
import com.standardapplied.helios.core.events.HeliosEvent.AssistantTextDelta;
import com.standardapplied.helios.core.events.HeliosEvent.AssistantThinkingComplete;
import com.standardapplied.helios.core.events.HeliosEvent.AssistantThinkingDelta;
import com.standardapplied.helios.core.events.HeliosEvent.MemoryRead;
import com.standardapplied.helios.core.events.HeliosEvent.MemoryWritten;
import com.standardapplied.helios.core.events.HeliosEvent.ToolCallCompleted;
import com.standardapplied.helios.core.events.HeliosEvent.ToolCallFailed;
import com.standardapplied.helios.core.events.HeliosEvent.ToolCallStarted;

/**
 * Encoders for assistant content, tool calls and memory mutations. Content — text, arguments, error
 * messages, block names — is written only in full detail.
 */
final class ContentEventEncoders {

  private ContentEventEncoders() {}

  static void assistantTextDelta(AssistantTextDelta e, JsonObjectWriter json, boolean full) {
    if (full) {
      json.string("text", e.text());
    }
  }

  static void assistantText(AssistantText e, JsonObjectWriter json, boolean full) {
    if (full) {
      json.string("fullText", e.fullText());
    }
  }

  static void assistantThinkingDelta(
      AssistantThinkingDelta e, JsonObjectWriter json, boolean full) {
    if (full) {
      json.string("thinkingText", e.thinkingText());
    }
  }

  static void assistantThinkingComplete(
      AssistantThinkingComplete e, JsonObjectWriter json, boolean full) {
    if (full) {
      json.string("fullThinking", e.fullThinking()).optionalString("signature", e.signature());
    }
  }

  static void toolCallStarted(ToolCallStarted e, JsonObjectWriter json, boolean full) {
    json.string("toolCallId", e.toolCallId()).string("toolName", e.toolName());
    if (full) {
      json.map("args", e.args());
    }
  }

  static void toolCallCompleted(ToolCallCompleted e, JsonObjectWriter json, boolean full) {
    json.string("toolCallId", e.toolCallId())
        .bool("success", e.result().success())
        .number("tookNanos", e.took().toNanos());
  }

  static void toolCallFailed(ToolCallFailed e, JsonObjectWriter json, boolean full) {
    json.string("toolCallId", e.toolCallId());
    if (full) {
      json.string("error", e.error());
    } else {
      json.bool("success", false).string("errorCategory", "tool_failed");
    }
  }

  static void memoryWritten(MemoryWritten e, JsonObjectWriter json, boolean full) {
    if (full) {
      json.string("blockName", e.blockName()).string("operation", e.operation());
    }
  }

  static void memoryRead(MemoryRead e, JsonObjectWriter json, boolean full) {
    if (full) {
      json.string("blockName", e.blockName());
    }
  }
}
