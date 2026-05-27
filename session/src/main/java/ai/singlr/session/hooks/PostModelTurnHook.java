/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import ai.singlr.core.model.Response;

/**
 * Hook fired after a model turn returns. Receives the model's {@link Response} for inspection.
 *
 * <p>Outcome semantics (spec §9.3): {@link HookOutcome.Continue} proceeds; {@link
 * HookOutcome.Inject} queues a synthetic user message before the next turn; {@link
 * HookOutcome.Stop} terminates the session. Mutation variants ({@link HookOutcome.MutateArgs},
 * {@link HookOutcome.MutateHistory}, {@link HookOutcome.MutateText}, {@link
 * HookOutcome.MutateResult}) and {@link HookOutcome.Block} are not meaningful at this phase.
 */
@FunctionalInterface
public non-sealed interface PostModelTurnHook extends Hook {

  /**
   * Inspect the just-returned model response.
   *
   * @param response the model's response; non-null
   * @param ctx the per-invocation context; non-null
   * @return the hook's decision
   */
  HookOutcome afterModelTurn(Response<?> response, HookContext ctx);

  @Override
  default String name() {
    return getClass().getSimpleName();
  }
}
