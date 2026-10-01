/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of a {@link HookRegistry} decisive-phase dispatch: the {@link HookOutcome} returned
 * to the orchestrator paired with the {@link Hook} that produced it.
 *
 * <p>The agent loop and {@code TurnRunner} branch on {@link #outcome()} to drive the loop and on
 * {@link #firingHook()} to attribute {@link ai.singlr.session.QueryEvent.HookFired
 * QueryEvent.HookFired} events to the actual hook (not the phase). A {@link HookOutcome.Continue}
 * outcome carries no firing hook — {@link #firingHook()} returns {@link Optional#empty()} for the
 * proceed path.
 *
 * @param firingHook the hook whose non-Continue outcome won, or {@code null} if every hook returned
 *     {@link HookOutcome.Continue}
 * @param outcome the resolved outcome
 */
public record HookDecision(Hook firingHook, HookOutcome outcome) {

  private static final HookDecision PROCEED = new HookDecision(null, HookOutcome.cont());

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code outcome} is null
   * @throws IllegalArgumentException if {@code outcome} is non-Continue but {@code firingHook} is
   *     null (every decisive outcome is attributable to the hook that produced it)
   */
  public HookDecision {
    Objects.requireNonNull(outcome, "outcome must not be null");
    if (!(outcome instanceof HookOutcome.Continue) && firingHook == null) {
      throw new IllegalArgumentException(
          "firingHook must not be null when outcome is non-Continue, got " + outcome);
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
   * Build a decision from a hook and the outcome it returned.
   *
   * @param hook the firing hook; non-null
   * @param outcome the non-Continue outcome; non-null
   * @return a fresh decision
   * @throws NullPointerException if {@code hook} or {@code outcome} is null
   * @throws IllegalArgumentException if {@code outcome} is {@link HookOutcome.Continue} (use {@link
   *     #proceed()} for that case)
   */
  public static HookDecision of(Hook hook, HookOutcome outcome) {
    Objects.requireNonNull(hook, "hook must not be null");
    Objects.requireNonNull(outcome, "outcome must not be null");
    if (outcome instanceof HookOutcome.Continue) {
      throw new IllegalArgumentException("use HookDecision.proceed() for Continue outcomes");
    }
    return new HookDecision(hook, outcome);
  }

  /**
   * Whether this decision is the proceed sentinel.
   *
   * @return {@code true} when no hook decided anything
   */
  public boolean shouldContinue() {
    return outcome instanceof HookOutcome.Continue;
  }

  /**
   * The hook that produced the non-Continue outcome, if any.
   *
   * @return the firing hook, or {@link Optional#empty()} on the proceed path
   */
  public Optional<Hook> firingHookOptional() {
    return Optional.ofNullable(firingHook);
  }
}
