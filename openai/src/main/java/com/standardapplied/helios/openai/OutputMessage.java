/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.StreamEvent;

/**
 * The message a streamed response writes: its output text, streamed as it arrives, and the refusal
 * the model gives instead, which completes with the full text its {@code done} event carries.
 */
final class OutputMessage {

  private final StringBuilder text = new StringBuilder();
  private final StringBuilder refusal = new StringBuilder();

  /** Output text {@code delta}: the text delta, unless there is none. */
  StreamEvent text(String delta) {
    if (delta == null) {
      return null;
    }
    text.append(delta);
    return new StreamEvent.TextDelta(delta);
  }

  /** Refusal text {@code delta}; a refusal is not output text, so it yields no event. */
  StreamEvent refusalDelta(String delta) {
    if (delta != null) {
      refusal.append(delta);
    }
    return null;
  }

  /** The refusal completed as {@code full}, or as streamed when the event carries no text. */
  StreamEvent refusalDone(String full) {
    if (full != null) {
      refusal.setLength(0);
      refusal.append(full);
    }
    return null;
  }

  /** The output text so far. */
  String text() {
    return text.toString();
  }

  /** The refusal so far, empty without one. */
  String refusal() {
    return refusal.toString();
  }
}
