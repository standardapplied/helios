/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.model;

import java.util.Objects;

/**
 * What a reasoning model is asked to do: stop reasoning, or reason at a named effort and return its
 * reasoning as stated. Set through {@link ModelConfig.Builder#withReasoning(Reasoning)}; an absent
 * reasoning sends nothing and leaves the provider's own defaults in force.
 *
 * <p>A provider sends exactly what is asked or rejects the configuration when the model is created,
 * naming the model, the rejected value and the values the model accepts. Nothing is clamped or
 * substituted: {@link Off} on a model that cannot stop reasoning is rejected, not replaced with its
 * lowest effort.
 */
public sealed interface Reasoning {

  /** Reasoning explicitly off. */
  record Off() implements Reasoning {}

  /**
   * Model-paced reasoning at a named effort, with a stated display.
   *
   * @param level the effort; non-null
   * @param display what of the reasoning is returned; non-null
   */
  record Effort(Level level, Display display) implements Reasoning {

    /**
     * @throws NullPointerException if {@code level} or {@code display} is null
     */
    public Effort {
      Objects.requireNonNull(level, "level must not be null");
      Objects.requireNonNull(display, "display must not be null");
    }
  }

  /** How much effort the model spends reasoning, lowest first. */
  enum Level {
    /** The least reasoning, below {@link #LOW}; only some models document it. */
    MINIMAL,

    /** Light reasoning. */
    LOW,

    /** Moderate reasoning. */
    MEDIUM,

    /** Thorough reasoning. */
    HIGH,

    /** Extra-deep reasoning, between {@link #HIGH} and {@link #MAX}. */
    XHIGH,

    /** Reasoning without a limit on depth. */
    MAX
  }

  /** What of the model's reasoning is returned. */
  enum Display {
    /** The reasoning is not returned. */
    HIDDEN,

    /** A readable summary of the reasoning. */
    SUMMARY,

    /** Only the progress notes the model writes between tool calls. */
    PROGRESS
  }
}
