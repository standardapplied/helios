/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Thinking configuration for the Claude Messages API. Which shape a model accepts is declared by
 * {@link com.standardapplied.helios.anthropic.AnthropicModelId#reasoning()}:
 *
 * <ul>
 *   <li><b>Adaptive</b>: {@code {"type":"adaptive","display":...}}, with thinking strength set by
 *       the sibling {@code output_config.effort} field. Built via {@link #adaptive(String)}.
 *   <li><b>Between tools</b> (Sonnet 5.5): {@code {"type":"between_tools"}}, no up-front thinking
 *       while the notes written between tool calls still return; any sibling field is a 400. Built
 *       via {@link #betweenTools()}.
 *   <li><b>Disabled</b>: {@code {"type":"disabled"}}. Built via {@link #disabled()}.
 * </ul>
 *
 * @param type {@code "adaptive"}, {@code "between_tools"} or {@code "disabled"}
 * @param display {@code "omitted"}, {@code "summarized"} or {@code "updates"} on the adaptive
 *     shape; null omits the field from the wire
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThinkingConfig(String type, String display) {

  /**
   * Adaptive thinking returned as {@code display}: {@code "omitted"} returns empty thinking blocks,
   * {@code "summarized"} a summary of the reasoning, {@code "updates"} only the progress notes
   * written between tool calls (beta {@code thinking-display-updates-2026-08-18}).
   */
  public static ThinkingConfig adaptive(String display) {
    return new ThinkingConfig("adaptive", display);
  }

  /** Thinking off, on models that think unless told otherwise. */
  public static ThinkingConfig disabled() {
    return new ThinkingConfig("disabled", null);
  }

  /** Sonnet 5.5's thinking off: no up-front thinking, with no other thinking field. */
  public static ThinkingConfig betweenTools() {
    return new ThinkingConfig("between_tools", null);
  }
}
