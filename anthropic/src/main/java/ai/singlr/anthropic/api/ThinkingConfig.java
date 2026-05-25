/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Extended thinking configuration for the Claude Messages API.
 *
 * <p>Two request shapes coexist depending on the model:
 *
 * <ul>
 *   <li><b>Legacy</b> (Opus 4.6, Sonnet 4.6): {@code {"type":"enabled","budget_tokens":N}}. Built
 *       via {@link #enabled(int)}.
 *   <li><b>Adaptive</b> (Opus 4.7+): {@code {"type":"adaptive","display":"summarized"}} with
 *       thinking strength controlled by a sibling {@code output_config.effort} field on the
 *       request. Built via {@link #adaptive()}; pair with {@link OutputConfig#LOW}, {@link
 *       OutputConfig#MEDIUM}, {@link OutputConfig#HIGH}, {@link OutputConfig#XHIGH}, or {@link
 *       OutputConfig#MAX}.
 * </ul>
 *
 * <p>Opus 4.7 rejects the legacy shape with {@code "thinking.type.enabled" is not supported for
 * this model}. {@link ai.singlr.anthropic.AnthropicModelId#usesAdaptiveThinking()} controls
 * dispatch.
 *
 * <h2>Display field</h2>
 *
 * Anthropic's API supports {@code display: "summarized"} (return the thinking summary) and {@code
 * display: "omitted"} (return empty {@code thinking} blocks with only the encrypted signature, for
 * the latency win of skipping summary streaming). On Opus 4.7 the API's silent default flipped to
 * {@code "omitted"}, which would zero out Helios's {@code ModelChunk.ThinkingDelta} event stream.
 * {@link #adaptive()} therefore pins {@code display = "summarized"} explicitly so callers continue
 * to receive thinking text events; callers who want the omitted-mode latency win use {@link
 * #adaptiveOmitted()} explicitly.
 *
 * @param type {@code "enabled"}, {@code "disabled"}, or {@code "adaptive"}
 * @param budgetTokens maximum tokens for thinking (only for {@code enabled})
 * @param display {@code "summarized"} or {@code "omitted"}; null omits the field from the wire
 *     (only meaningful when {@code type=adaptive})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThinkingConfig(
    String type, @JsonProperty("budget_tokens") Integer budgetTokens, String display) {

  /** Legacy {@code type=enabled} shape with explicit budget. Used by Opus 4.6 / Sonnet 4.6. */
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
   * carrying only the encrypted signature. Trade summary visibility for lower time-to-first-text on
   * Opus 4.7. Multi-turn conversations are unaffected (the signature still lets the model
   * reconstruct internal state).
   */
  public static ThinkingConfig adaptiveOmitted() {
    return new ThinkingConfig("adaptive", null, "omitted");
  }

  /** Explicit disabled shape; rarely used since omitting the field has the same effect. */
  public static ThinkingConfig disabled() {
    return new ThinkingConfig("disabled", null, null);
  }
}
