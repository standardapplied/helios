/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Output-side configuration for the Claude Messages API. Sibling field of {@code thinking}; pairs
 * with the {@link ThinkingConfig#adaptive()} shape on Opus 4.7+ to control thinking strength.
 *
 * <p>For legacy models that use {@link ThinkingConfig#enabled(int)} the request omits {@code
 * output_config} entirely — thinking strength rides on {@code budget_tokens}.
 *
 * @param effort one of {@code "low"}, {@code "medium"}, or {@code "high"}; {@code null} omits
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutputConfig(String effort) {

  /** Effort {@code low}. Maps from {@link ai.singlr.core.model.ThinkingLevel} MINIMAL/LOW. */
  public static final OutputConfig LOW = new OutputConfig("low");

  /** Effort {@code medium}. Maps from {@link ai.singlr.core.model.ThinkingLevel} MEDIUM. */
  public static final OutputConfig MEDIUM = new OutputConfig("medium");

  /** Effort {@code high}. Maps from {@link ai.singlr.core.model.ThinkingLevel} HIGH. */
  public static final OutputConfig HIGH = new OutputConfig("high");

  /**
   * Effort {@code xhigh} — extra-deep reasoning with extended exploration. Opus 4.7 only on the
   * native wire. Maps from {@link ai.singlr.core.model.ThinkingLevel#XHIGH}.
   */
  public static final OutputConfig XHIGH = new OutputConfig("xhigh");

  /**
   * Effort {@code max} — unbounded reasoning. Available on every adaptive-capable model (Opus 4.7,
   * Opus 4.6, Sonnet 4.6). Maps from {@link ai.singlr.core.model.ThinkingLevel#MAX}.
   */
  public static final OutputConfig MAX = new OutputConfig("max");
}
