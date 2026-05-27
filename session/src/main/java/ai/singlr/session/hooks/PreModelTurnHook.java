/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import ai.singlr.core.model.Message;
import java.util.List;

/**
 * Hook fired before a model turn. Receives the conversation history the loop is about to send.
 *
 * <p>Outcome semantics (spec §9.3): {@link HookOutcome.Continue} proceeds; {@link
 * HookOutcome.MutateHistory} replaces the conversation history the model will see (the
 * BYO-compactor-as-hook path); {@link HookOutcome.Inject} queues a synthetic user message before
 * the call; {@link HookOutcome.Stop} terminates the session. Other mutation variants and {@link
 * HookOutcome.Block} are not meaningful at this phase.
 */
@FunctionalInterface
public non-sealed interface PreModelTurnHook extends Hook {

  /**
   * Inspect the impending model turn.
   *
   * @param history the history about to be sent to the model; non-null, immutable
   * @param ctx the per-invocation context; non-null
   * @return the hook's decision
   */
  HookOutcome beforeModelTurn(List<Message> history, HookContext ctx);

  @Override
  default String name() {
    return getClass().getSimpleName();
  }
}
