/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.StreamEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The reasoning summary of a streamed response, one part per summary index of each reasoning item:
 * each part streams as thinking and completes with the full text its {@code done} event carries.
 * The Responses API signs no summary, so a completed part carries no signature. A part's first text
 * after another part's carries the blank line {@link #text()} puts between them, so the deltas add
 * up to the thinking.
 */
final class ReasoningSummary {

  private static final String SEPARATOR = "\n\n";

  private final Map<String, StringBuilder> parts = new LinkedHashMap<>();

  /** {@code delta} streamed into the part {@code summaryIndex} of item {@code itemId}. */
  StreamEvent delta(String itemId, Integer summaryIndex, String delta) {
    if (delta == null) {
      return null;
    }
    var part = part(itemId, summaryIndex);
    var separator = delta.isEmpty() ? "" : separatorBefore(part);
    part.append(delta);
    return new StreamEvent.ThinkingDelta(separator + delta);
  }

  /**
   * The completion of a part as {@code text}: its thinking, unless the part is empty. A part that
   * streamed no delta completes as one delta of its whole text, so a chunk stream still sees it.
   */
  StreamEvent done(String itemId, Integer summaryIndex, String text) {
    var part = part(itemId, summaryIndex);
    var streamed = !part.isEmpty();
    var separator = separatorBefore(part);
    if (text != null) {
      part.setLength(0);
      part.append(text);
    }
    if (part.isEmpty()) {
      return null;
    }
    return streamed
        ? new StreamEvent.ThinkingComplete(part.toString(), null)
        : new StreamEvent.ThinkingDelta(separator + part);
  }

  /** The parts that hold text, in arrival order, a blank line apart; {@code null} without one. */
  String text() {
    var text =
        parts.values().stream()
            .filter(part -> !part.isEmpty())
            .collect(Collectors.joining(SEPARATOR));
    return text.isEmpty() ? null : text;
  }

  /** The separator a first text of {@code part} needs: none unless another part has text. */
  private String separatorBefore(StringBuilder part) {
    return part.isEmpty() && text() != null ? SEPARATOR : "";
  }

  private StringBuilder part(String itemId, Integer summaryIndex) {
    return parts.computeIfAbsent(itemId + "#" + summaryIndex, key -> new StringBuilder());
  }
}
