/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import ai.singlr.session.UserMessage;

/**
 * Hook fired when a user message is dequeued from the steering queue. PII redactors, prompt
 * routers, and "drop empty messages" filters live here.
 *
 * <p>Outcome semantics (spec §9.3): {@link HookOutcome.Continue} proceeds; {@link
 * HookOutcome.MutateText} replaces the message text; {@link HookOutcome.Block} drops the message;
 * {@link HookOutcome.Stop} terminates the session. {@link HookOutcome.Inject} is not meaningful
 * here (the loop is already handling a user message).
 */
@FunctionalInterface
public non-sealed interface OnUserMessageHook extends Hook {

  /**
   * Inspect the user message that just arrived.
   *
   * @param msg the message; non-null
   * @param ctx the per-invocation context; non-null
   * @return the hook's decision
   */
  HookOutcome onUserMessage(UserMessage msg, HookContext ctx);

  @Override
  default String name() {
    return getClass().getSimpleName();
  }
}
