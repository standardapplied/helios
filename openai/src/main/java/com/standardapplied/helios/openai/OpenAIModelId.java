/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import com.standardapplied.helios.core.provider.ReasoningSupport;
import java.util.EnumSet;
import java.util.Set;

/**
 * Supported OpenAI model identifiers.
 *
 * <p>Each enum constant maps to a specific model available through the Responses API.
 */
public enum OpenAIModelId {
  // maxOutputTokens reflects the documented per-model output ceiling at time of writing —
  // operators can override per-call via ModelConfig.Builder.withMaxOutputTokens. Reasoning models
  // (o3, o4-mini) carry higher caps because their output includes reasoning tokens.
  // ReasoningRules per model (model pages and the reasoning, GPT-5.4 and GPT-6 guides, 2026-10-08):
  // gpt-6-astra and gpt-6.1-sol take low..max and reject none; gpt-6-sol, gpt-6-luna and the
  // gpt-5.6
  // family take none..max; gpt-5.5 and the gpt-5.4 family take none..xhigh, gpt-5.4 defaulting to
  // none; o-series take low..high; gpt-4.1 and gpt-4o do not reason. temperature / top_p ride only
  // at effort none on the GPT-5.4+ reasoning models, never on o-series or gpt-6-astra/6.1-sol.
  GPT_6_ASTRA("gpt-6-astra", 1_050_000, 128_000, ReasoningRules.ALWAYS_REASONS),
  GPT_6_1_SOL("gpt-6.1-sol", 1_050_000, 128_000, ReasoningRules.ALWAYS_REASONS),
  GPT_6_SOL("gpt-6-sol", 1_050_000, 128_000, ReasoningRules.NONE_TO_MAX),
  GPT_6_LUNA("gpt-6-luna", 1_050_000, 128_000, ReasoningRules.NONE_TO_MAX),
  GPT_5_6("gpt-5.6", 1_050_000, 128_000, ReasoningRules.NONE_TO_MAX),
  GPT_5_6_SOL("gpt-5.6-sol", 1_050_000, 128_000, ReasoningRules.NONE_TO_MAX),
  GPT_5_6_TERRA("gpt-5.6-terra", 1_050_000, 128_000, ReasoningRules.NONE_TO_MAX),
  GPT_5_6_LUNA("gpt-5.6-luna", 1_050_000, 128_000, ReasoningRules.NONE_TO_MAX),
  GPT_5_5("gpt-5.5", 1_050_000, 128_000, ReasoningRules.NONE_TO_XHIGH),
  GPT_5_4("gpt-5.4", 1_050_000, 128_000, ReasoningRules.NONE_BY_DEFAULT_TO_XHIGH),
  GPT_5_4_MINI("gpt-5.4-mini", 400_000, 128_000, ReasoningRules.NONE_BY_DEFAULT_TO_XHIGH),
  GPT_5_4_NANO("gpt-5.4-nano", 400_000, 128_000, ReasoningRules.NONE_BY_DEFAULT_TO_XHIGH),
  GPT_4_1("gpt-4.1", 1_000_000, 32_000, ReasoningRules.NOT_REASONING),
  GPT_4_1_MINI("gpt-4.1-mini", 1_000_000, 32_000, ReasoningRules.NOT_REASONING),
  GPT_4_1_NANO("gpt-4.1-nano", 1_000_000, 16_000, ReasoningRules.NOT_REASONING),
  GPT_4O("gpt-4o", 128_000, 16_384, ReasoningRules.NOT_REASONING),
  GPT_4O_MINI("gpt-4o-mini", 128_000, 16_384, ReasoningRules.NOT_REASONING),
  O3("o3", 200_000, 100_000, ReasoningRules.LOW_TO_HIGH),
  O4_MINI("o4-mini", 200_000, 100_000, ReasoningRules.LOW_TO_HIGH);

  /** How a model is told to stop reasoning. */
  public enum Off {
    /** It cannot stop: {@code Reasoning.Off} is rejected. */
    REJECTED,

    /** The {@code reasoning} field is left out; the model does not reason. */
    OMITTED,

    /** {@code reasoning.effort=none}. */
    NONE
  }

  /** When a model accepts {@code temperature} and {@code top_p}. */
  public enum Sampling {
    /** Always. */
    ACCEPTED,

    /** Only with {@code Reasoning.Off}, sent as effort {@code none}. */
    WITH_OFF,

    /** Unless {@code Reasoning.Effort} is set: the model's default effort is {@code none}. */
    WITHOUT_EFFORT,

    /** Never: either one is rejected whenever it is set. */
    REJECTED,

    /** Whatever is set is sent: the API judges a model the catalogue does not know. */
    UNCHECKED
  }

  /**
   * What a model accepts for reasoning and sampling, and how it is told to stop reasoning. An
   * accepted {@code Reasoning.Effort} is sent as {@code reasoning.effort}, with {@code
   * reasoning.summary=auto} when a summary is asked for.
   */
  public enum ReasoningRules {
    /** Always reasons (gpt-6-astra, gpt-6.1-sol). */
    ALWAYS_REASONS(Off.REJECTED, Level.LOW, Level.MAX, Sampling.REJECTED),

    /** {@code none} up to {@code max} (gpt-6-sol, gpt-6-luna, gpt-5.6 family). */
    NONE_TO_MAX(Off.NONE, Level.LOW, Level.MAX, Sampling.WITH_OFF),

    /** {@code none} up to {@code xhigh} (gpt-5.5). */
    NONE_TO_XHIGH(Off.NONE, Level.LOW, Level.XHIGH, Sampling.WITH_OFF),

    /** {@code none} up to {@code xhigh}, {@code none} by default (gpt-5.4 family). */
    NONE_BY_DEFAULT_TO_XHIGH(Off.NONE, Level.LOW, Level.XHIGH, Sampling.WITHOUT_EFFORT),

    /** {@code low} up to {@code high} (o-series). */
    LOW_TO_HIGH(Off.REJECTED, Level.LOW, Level.HIGH, Sampling.REJECTED),

    /** Does not reason (gpt-4.1, gpt-4o families). */
    NOT_REASONING(Off.OMITTED, null, null, Sampling.ACCEPTED),

    /**
     * A model the catalogue does not know: accepts every level and both displays the API can send,
     * spelling off as {@code none}; the API judges what the model accepts.
     */
    UNCATALOGUED(Off.NONE, Level.MINIMAL, Level.MAX, Sampling.UNCHECKED);

    private final Off off;
    private final ReasoningSupport support;
    private final Sampling sampling;

    ReasoningRules(Off off, Level lowest, Level highest, Sampling sampling) {
      var reasons = lowest != null;
      this.off = off;
      this.support =
          new ReasoningSupport(
              off != Off.REJECTED,
              reasons ? EnumSet.range(lowest, highest) : Set.of(),
              reasons ? EnumSet.of(Display.HIDDEN, Display.SUMMARY) : Set.of());
      this.sampling = sampling;
    }

    /**
     * How the model is told to stop reasoning.
     *
     * @return the off spelling; {@link Off#REJECTED} when it cannot stop
     */
    public Off off() {
      return off;
    }

    /**
     * The reasoning values the model accepts.
     *
     * @return the support
     */
    public ReasoningSupport support() {
      return support;
    }

    /**
     * When the model accepts {@code temperature} and {@code top_p}.
     *
     * @return the sampling rule
     */
    public Sampling sampling() {
      return sampling;
    }
  }

  private final String id;
  private final int contextWindow;
  private final int maxOutputTokens;
  private final ReasoningRules reasoning;

  OpenAIModelId(String id, int contextWindow, int maxOutputTokens, ReasoningRules reasoning) {
    this.id = id;
    this.contextWindow = contextWindow;
    this.maxOutputTokens = maxOutputTokens;
    this.reasoning = reasoning;
  }

  /**
   * What this model accepts for reasoning and sampling. Read only by the request builder, the one
   * place that turns it into request fields.
   *
   * @return the rules; non-null
   */
  public ReasoningRules reasoning() {
    return reasoning;
  }

  /**
   * Returns the API model identifier string.
   *
   * @return the model ID used in API requests
   */
  public String id() {
    return id;
  }

  /**
   * Returns the context window size in tokens.
   *
   * @return the context window size
   */
  public int contextWindow() {
    return contextWindow;
  }

  /**
   * Returns the maximum output tokens this model can generate in a single response. Used as the
   * fallback when {@code ModelConfig.maxOutputTokens()} is unset.
   *
   * @return the per-model output ceiling
   */
  public int maxOutputTokens() {
    return maxOutputTokens;
  }

  /**
   * Finds an OpenAIModelId by its string identifier.
   *
   * @param id the model identifier string
   * @return the matching OpenAIModelId, or null if not found
   */
  public static OpenAIModelId fromId(String id) {
    if (Strings.isBlank(id)) {
      return null;
    }
    for (var model : values()) {
      if (model.id.equals(id)) {
        return model;
      }
    }
    return null;
  }

  /**
   * Checks if the given model ID is supported.
   *
   * @param id the model identifier string
   * @return true if the model is supported
   */
  public static boolean isSupported(String id) {
    return fromId(id) != null;
  }
}
