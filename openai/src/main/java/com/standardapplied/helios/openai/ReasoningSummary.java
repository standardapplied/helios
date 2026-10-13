/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.StreamEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The reasoning summary of a streamed response, one part per summary index of each reasoning item:
 * each part streams as thinking and completes with the full text its {@code done} event carries.
 * The Responses API signs no summary, so a completed part carries no signature.
 */
final class ReasoningSummary {

  private final Map<String, StringBuilder> parts = new LinkedHashMap<>();

  /** {@code delta} streamed into the part {@code summaryIndex} of item {@code itemId}. */
  StreamEvent delta(String itemId, Integer summaryIndex, String delta) {
    if (delta == null) {
      return null;
    }
    part(itemId, summaryIndex).append(delta);
    return new StreamEvent.ThinkingDelta(delta);
  }

  /** The completion of a part as {@code text}: its thinking, unless the part is empty. */
  StreamEvent done(String itemId, Integer summaryIndex, String text) {
    var part = part(itemId, summaryIndex);
    if (text != null) {
      part.setLength(0);
      part.append(text);
    }
    return part.isEmpty() ? null : new StreamEvent.ThinkingComplete(part.toString(), null);
  }

  /** The parts that hold text, in arrival order, a blank line apart; {@code null} without one. */
  String text() {
    var text =
        parts.values().stream().filter(part -> !part.isEmpty()).collect(Collectors.joining("\n\n"));
    return text.isEmpty() ? null : text;
  }

  private StringBuilder part(String itemId, Integer summaryIndex) {
    return parts.computeIfAbsent(itemId + "#" + summaryIndex, key -> new StringBuilder());
  }
}
