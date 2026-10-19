/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.hooks;

import com.standardapplied.helios.core.common.Strings;
import java.util.Objects;

/**
 * The outcome of a {@link HookRegistry} decisive-phase dispatch: the {@link HookOutcome} returned
 * to the orchestrator paired with the name of the {@link Hook} that produced it, as the registry
 * read it when it was built.
 *
 * <p>The agent loop and {@code TurnRunner} branch on {@link #outcome()} to drive the loop and on
 * {@link #firingHookName()} to attribute {@link
 * com.standardapplied.helios.session.QueryEvent.HookFired QueryEvent.HookFired} events to the
 * actual hook (not the phase). A {@link HookOutcome.Continue} outcome carries no name.
 *
 * @param firingHookName the name of the hook whose non-Continue outcome won, or {@code null} if
 *     every hook returned {@link HookOutcome.Continue}
 * @param outcome the resolved outcome
 */
public record HookDecision(String firingHookName, HookOutcome outcome) {

  private static final HookDecision PROCEED = new HookDecision(null, HookOutcome.cont());

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code outcome} is null
   * @throws IllegalArgumentException if {@code outcome} is non-Continue and {@code firingHookName}
   *     is null or blank (every decisive outcome is attributable to the hook that produced it), or
   *     if {@code outcome} is Continue and {@code firingHookName} is not null
   */
  public HookDecision {
    Objects.requireNonNull(outcome, "outcome must not be null");
    if (outcome instanceof HookOutcome.Continue) {
      if (firingHookName != null) {
        throw new IllegalArgumentException(
            "firingHookName must be null when outcome is Continue, got " + firingHookName);
      }
    } else if (Strings.isBlank(firingHookName)) {
      throw new IllegalArgumentException(
          "firingHookName must not be blank when outcome is non-Continue, got " + outcome);
    }
  }

  /**
   * The shared "no hook decided anything" instance — used as the default return when every hook in
   * the phase returned {@link HookOutcome.Continue}.
   *
   * @return the singleton proceed decision
   */
  public static HookDecision proceed() {
    return PROCEED;
  }

  /**
   * Build a decision from the firing hook's name and the outcome it returned.
   *
   * @param firingHookName the firing hook's name; non-blank
   * @param outcome the non-Continue outcome; non-null
   * @return a fresh decision
   * @throws NullPointerException if {@code outcome} is null
   * @throws IllegalArgumentException if {@code outcome} is {@link HookOutcome.Continue} (use {@link
   *     #proceed()} for that case), or {@code firingHookName} is null or blank
   */
  public static HookDecision of(String firingHookName, HookOutcome outcome) {
    if (outcome instanceof HookOutcome.Continue) {
      throw new IllegalArgumentException("use HookDecision.proceed() for Continue outcomes");
    }
    return new HookDecision(firingHookName, outcome);
  }

  /**
   * Whether this decision is the proceed sentinel.
   *
   * @return {@code true} when no hook decided anything
   */
  public boolean shouldContinue() {
    return outcome instanceof HookOutcome.Continue;
  }
}
