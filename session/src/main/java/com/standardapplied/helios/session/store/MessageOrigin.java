/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

import com.standardapplied.helios.core.model.Message;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Who wrote a message the session added to its history itself, rather than the caller or the
 * model's answer: the tag rides in {@link Message#metadata()} under {@link #KEY}, as the value's
 * lower-case name with hyphens ({@code schema-correction}, {@code rejected-attempt}, {@code
 * hook-inject}, {@code partial-turn}), so every store keeps it and no provider sends it. A message
 * without the tag is the caller's input or the model's own turn.
 */
public enum MessageOrigin {

  /** The user message that tells the model why its structured output was rejected. */
  SCHEMA_CORRECTION,

  /** The model's structured output that failed its schema, kept so the model sees it on retry. */
  REJECTED_ATTEMPT,

  /** The user message a hook's {@code Inject} outcome queued. */
  HOOK_INJECT,

  /**
   * The text a model call streamed before its stream ended without completing; its tool calls and
   * reasoning are not kept.
   */
  PARTIAL_TURN;

  /** The {@link Message#metadata()} key the tag is stored under. */
  public static final String KEY = "helios.origin";

  private final String value = name().toLowerCase(Locale.ROOT).replace('_', '-');

  /**
   * The metadata that tags a message with this origin.
   *
   * @return a map of {@link #KEY} to this origin's value
   */
  public Map<String, String> metadata() {
    return Map.of(KEY, value);
  }

  /**
   * The origin {@code message} is tagged with.
   *
   * @param message the message; non-null
   * @return its origin; empty when it carries no {@link #KEY}, or a value no origin spells
   * @throws NullPointerException if {@code message} is null
   */
  public static Optional<MessageOrigin> of(Message message) {
    var metadata = message.metadata();
    var tag = metadata == null ? null : metadata.get(KEY);
    return Arrays.stream(values()).filter(origin -> origin.value.equals(tag)).findFirst();
  }
}
