/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.hooks;

import com.standardapplied.helios.core.model.ToolCall;
import java.util.List;
import java.util.Objects;

/**
 * What the {@link PreToolUseHook} chain made of one tool call: the call as the last hook left it,
 * every argument mutation in the order the hooks made it, and the decision that ended the chain.
 *
 * @param call the final call, the one the session authorizes and runs; non-null
 * @param mutations every {@link HookOutcome.MutateArgs} decision, in order; non-null, copied
 * @param decision the {@link HookOutcome.Block}, {@link HookOutcome.Inject} or {@link
 *     HookOutcome.Stop} decision that ended the chain, or {@link HookDecision#proceed()} when none
 *     did; non-null
 */
public record PreToolUseResult(ToolCall call, List<HookDecision> mutations, HookDecision decision) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null or {@code mutations} holds a null
   * @throws IllegalArgumentException if a mutation is not {@link HookOutcome.MutateArgs}, or {@code
   *     decision} is
   */
  public PreToolUseResult {
    Objects.requireNonNull(call, "call must not be null");
    mutations = List.copyOf(Objects.requireNonNull(mutations, "mutations must not be null"));
    Objects.requireNonNull(decision, "decision must not be null");
    for (var mutation : mutations) {
      if (!(mutation.outcome() instanceof HookOutcome.MutateArgs)) {
        throw new IllegalArgumentException("not an argument mutation: " + mutation);
      }
    }
    if (decision.outcome() instanceof HookOutcome.MutateArgs) {
      throw new IllegalArgumentException("an argument mutation does not end the chain");
    }
  }
}
