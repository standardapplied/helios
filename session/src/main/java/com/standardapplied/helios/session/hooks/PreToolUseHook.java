/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import ai.singlr.core.model.ToolCall;

/**
 * Hook fired before a tool call is dispatched. Permission checks, secret scanners, and tool-input
 * mutations live here.
 *
 * <p>Outcome semantics (spec §9.3): {@link HookOutcome.Continue} proceeds; {@link
 * HookOutcome.MutateArgs} runs the tool with replacement arguments; {@link HookOutcome.Block}
 * refuses the call and emits a {@code ToolBlocked} event; {@link HookOutcome.Inject} skips the tool
 * and injects a synthetic user message before the next turn; {@link HookOutcome.Stop} terminates
 * the session.
 */
@FunctionalInterface
public non-sealed interface PreToolUseHook extends Hook {

  /**
   * Inspect the impending tool call.
   *
   * @param call the call about to be dispatched; non-null
   * @param ctx the per-invocation context; non-null
   * @return the hook's decision
   */
  HookOutcome beforeTool(ToolCall call, HookContext ctx);

  @Override
  default String name() {
    return getClass().getSimpleName();
  }
}
