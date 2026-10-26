/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.hooks;

import com.standardapplied.helios.core.model.Response;

/**
 * Hook fired after a model turn returns. Receives the model's {@link Response} for inspection.
 *
 * <p>Outcome semantics (spec §9.3): {@link HookOutcome.Continue} proceeds; {@link
 * HookOutcome.Inject} queues a synthetic user message before the next turn; {@link
 * HookOutcome.Stop} terminates the session.
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
  HookOutcome.PostModelTurn afterModelTurn(Response<?> response, HookContext ctx);

  @Override
  default String name() {
    return HookNames.defaultName(this);
  }
}
