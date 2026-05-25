/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

/**
 * Provider-agnostic effort tier for extended-thinking / reasoning models.
 *
 * <p>Each provider maps these to its own native vocabulary; some clamp to a narrower range when the
 * provider exposes fewer tiers:
 *
 * <ul>
 *   <li><b>Anthropic Opus 4.7 (adaptive):</b> {@code low} / {@code medium} / {@code high} / {@code
 *       xhigh} / {@code max} on the {@code output_config.effort} sibling.
 *   <li><b>Anthropic Opus 4.6 / Sonnet 4.6 (legacy enabled+budget_tokens):</b> MINIMAL→1024,
 *       LOW→4096, MEDIUM→10000, HIGH→32000. {@link #XHIGH} and {@link #MAX} have no equivalent on
 *       the legacy shape and are rejected at request build with {@link IllegalArgumentException}
 *       rather than silently downgraded.
 *   <li><b>OpenAI Responses API:</b> {@code low} / {@code medium} / {@code high} only — {@link
 *       #XHIGH} and {@link #MAX} clamp to {@code high}.
 *   <li><b>Gemini:</b> {@code low} / {@code medium} / {@code high} only — {@link #XHIGH} and {@link
 *       #MAX} clamp to {@code high}.
 * </ul>
 */
public enum ThinkingLevel {
  /** No reasoning trace included. */
  NONE,

  /** Brief reasoning summary. */
  MINIMAL,

  /** Reduced reasoning trace. */
  LOW,

  /** Moderate reasoning detail. */
  MEDIUM,

  /** Full reasoning trace with complete thought process. */
  HIGH,

  /**
   * Extra-deep reasoning with extended exploration. Anthropic Opus 4.7 only on the native wire;
   * other providers clamp to {@link #HIGH}.
   */
  XHIGH,

  /**
   * Unbounded reasoning — "always thinks with no constraints on thinking depth". Anthropic
   * adaptive-capable models (Opus 4.7, Opus 4.6, Sonnet 4.6) accept {@code "max"} on the native
   * wire; legacy enabled+budget_tokens has no equivalent and is rejected. Other providers clamp to
   * {@link #HIGH}.
   */
  MAX
}
