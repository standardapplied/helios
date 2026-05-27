/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import ai.singlr.core.model.Response;

/**
 * Hook fired when the model wants to stop. Gates termination with the added power to inject
 * follow-up turns — the v2 programmatic completion-control surface.
 *
 * <p>Outcome semantics (spec §9.3): {@link HookOutcome.Continue} confirms the stop; {@link
 * HookOutcome.Inject} treats the response as not-terminal and queues a synthetic user message for
 * the next turn; {@link HookOutcome.Stop} confirms termination with a custom result string.
 * Mutation variants and {@link HookOutcome.Block} are not meaningful at this phase.
 *
 * <p>This is the canonical hook surface for "devil's advocate" patterns: a {@code PreStopHook}
 * calls {@code ctx.model().chat(...)} with the draft answer + a critique prompt and returns {@link
 * HookOutcome.Inject} when the critique is sharp enough to warrant another turn.
 */
@FunctionalInterface
public non-sealed interface PreStopHook extends Hook {

  /**
   * Inspect the response the loop is about to declare terminal.
   *
   * @param stopResponse the model's stop-flagged response; non-null
   * @param ctx the per-invocation context; non-null
   * @return the hook's decision
   */
  HookOutcome beforeStop(Response<?> stopResponse, HookContext ctx);

  @Override
  default String name() {
    return getClass().getSimpleName();
  }
}
