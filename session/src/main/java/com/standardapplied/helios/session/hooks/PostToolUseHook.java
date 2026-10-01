/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import ai.singlr.core.model.ToolCall;
import ai.singlr.core.tool.ToolResult;

/**
 * Hook fired after a tool call returns or fails. Result scrubbers, post-call validators, and "must
 * call {@code validate_X} after every {@code propose_X}" patterns live here.
 *
 * <p>Outcome semantics (spec §9.3): {@link HookOutcome.Continue} proceeds; {@link
 * HookOutcome.MutateResult} rewrites the tool result output; {@link HookOutcome.Inject} queues a
 * synthetic user message before the next turn; {@link HookOutcome.Stop} terminates the session.
 * {@link HookOutcome.Block} is not meaningful at this phase.
 */
@FunctionalInterface
public non-sealed interface PostToolUseHook extends Hook {

  /**
   * Inspect the just-completed tool call.
   *
   * @param call the call that ran; non-null
   * @param result the result the tool produced; non-null
   * @param ctx the per-invocation context; non-null
   * @return the hook's decision
   */
  HookOutcome afterTool(ToolCall call, ToolResult result, HookContext ctx);

  @Override
  default String name() {
    return getClass().getSimpleName();
  }
}
