/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Thinking configuration for the Claude Messages API.
 *
 * <p>Four request shapes coexist; {@link ai.singlr.anthropic.AnthropicModelId#thinkingShape()}
 * controls dispatch:
 *
 * <ul>
 *   <li><b>Extended</b> (Haiku 4.5): {@code {"type":"enabled","budget_tokens":N}}. Built via {@link
 *       #enabled(int)}. Deprecated on Opus 4.6 and Sonnet 4.6, where Helios sends adaptive instead,
 *       and rejected with a 400 from Opus 4.7 on.
 *   <li><b>Adaptive</b> (Opus 4.6 and later, Sonnet 4.6 and later, Fable, Mythos): {@code
 *       {"type":"adaptive","display":"summarized"}} with thinking strength controlled by a sibling
 *       {@code output_config.effort} field on the request. Built via {@link #adaptive()}; pair with
 *       an {@link OutputConfig} effort.
 *   <li><b>Between tools</b> (Sonnet 5.5 only): {@code {"type":"between_tools"}}, the lowest
 *       setting on a model that rejects {@code disabled}. Built via {@link #betweenTools()}.
 *   <li><b>Disabled</b> (Opus 5, Sonnet 5, and models whose default is already off): {@code
 *       {"type":"disabled"}}. Built via {@link #disabled()}.
 * </ul>
 *
 * <h2>Display field</h2>
 *
 * Anthropic's API supports {@code display: "summarized"} (return the thinking summary) and {@code
 * display: "omitted"} (return empty {@code thinking} blocks with only the encrypted signature, for
 * the latency win of skipping summary streaming). From Opus 4.7 on the API's silent default is
 * {@code "omitted"}, which would zero out Helios's thinking text. {@link #adaptive()} therefore
 * pins {@code display = "summarized"} explicitly so callers continue to receive thinking text —
 * including the progress notes Fable 5.1, Opus 5.5 and Sonnet 5.5 write between tool calls, which
 * arrive as {@code thinking} blocks rather than {@code text}. Callers who want the omitted-mode
 * latency win use {@link #adaptiveOmitted()} explicitly.
 *
 * @param type {@code "enabled"}, {@code "disabled"}, {@code "adaptive"}, or {@code "between_tools"}
 * @param budgetTokens maximum tokens for thinking (only for {@code enabled})
 * @param display {@code "summarized"} or {@code "omitted"}; null omits the field from the wire
 *     (only meaningful when {@code type=adaptive})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThinkingConfig(
    String type, @JsonProperty("budget_tokens") Integer budgetTokens, String display) {

  /** Extended {@code type=enabled} shape with explicit budget. Used by Haiku 4.5. */
  public static ThinkingConfig enabled(int budgetTokens) {
    return new ThinkingConfig("enabled", budgetTokens, null);
  }

  /**
   * Adaptive shape with {@code display="summarized"} so callers continue to receive thinking deltas
   * through {@link ai.singlr.core.model.ModelChunk.ThinkingDelta}. Effort is set via the request's
   * sibling {@code output_config.effort} field, not on this object.
   */
  public static ThinkingConfig adaptive() {
    return new ThinkingConfig("adaptive", null, "summarized");
  }

  /**
   * Adaptive shape with {@code display="omitted"} — Anthropic's API returns empty thinking blocks
   * carrying only the encrypted signature. Trade summary visibility for lower time-to-first-text.
   * Multi-turn conversations are unaffected (the signature still lets the model reconstruct
   * internal state).
   */
  public static ThinkingConfig adaptiveOmitted() {
    return new ThinkingConfig("adaptive", null, "omitted");
  }

  /**
   * Explicit disabled shape. Required to turn thinking off on models that think when the field is
   * omitted (Opus 5, Sonnet 5); elsewhere omitting the field has the same effect.
   */
  public static ThinkingConfig disabled() {
    return new ThinkingConfig("disabled", null, null);
  }

  /**
   * Sonnet 5.5's lowest thinking setting: no up-front thinking, while the progress notes the model
   * writes between tool calls still return as {@code thinking} blocks with text. The API rejects
   * any sibling field ({@code display}, {@code budget_tokens}) and any effort above {@code high}.
   */
  public static ThinkingConfig betweenTools() {
    return new ThinkingConfig("between_tools", null, null);
  }
}
