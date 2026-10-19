/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.hooks;

import com.standardapplied.helios.core.model.ToolCall;

/**
 * Guard fired before a tool call is dispatched. Secret scanners, custom policy checks and
 * tool-input mutations live here; the session's permission check runs after every one of them, on
 * the call they leave.
 *
 * <p>The hooks of this phase chain, in priority order. Outcome semantics: {@link
 * HookOutcome.Continue} passes the call unchanged to the next hook; {@link HookOutcome.MutateArgs}
 * passes the same call with replacement arguments to the next hook; {@link HookOutcome.Block}
 * refuses the call and emits a {@code ToolBlocked} event; {@link HookOutcome.Inject} skips the tool
 * and injects a synthetic user message before the next turn; {@link HookOutcome.Stop} terminates
 * the session. The last three end the chain.
 *
 * <p>A guard fails closed: a {@link RuntimeException} thrown from {@link #beforeTool} or a {@code
 * null} outcome refuses the call as a {@code Block} by this hook, and no later hook runs. An {@link
 * Error} escapes and ends the session.
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
    return HookNames.defaultName(this);
  }
}
