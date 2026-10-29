/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.session.hooks.HookContext;
import java.util.List;

/**
 * Shrinks a long conversation history so the next model turn fits inside the provider's context
 * window. Invoked by the agent loop after a model turn when {@code TokenCounter.count(history) /
 * SessionLimits.maxContextTokens() >= 0.95} (the {@code QueryEvent.ContextWarning} watermark at
 * 0.85 fires earlier as a heads-up).
 *
 * <p>Implementations are free to drop, merge, summarise, or vector-recall. The contract is minimal:
 *
 * <ul>
 *   <li>The returned list MUST be non-null.
 *   <li>The returned list MAY be the same instance as {@code history} (no-op).
 *   <li>The returned list MUST be self-consistent against the provider's tool-call invariants —
 *       e.g. don't drop a tool-result row while keeping its preceding assistant tool-call row, or
 *       the next model turn will refuse the malformed history.
 * </ul>
 *
 * <p>The default ships {@link SummarizingCompactor}: keep the system prompt and the pending user
 * turn, replace the rest of the conversation with one summary from a single model call.
 *
 * <h2>Three tiers of library-user control</h2>
 *
 * <ol>
 *   <li><b>Default</b> — do nothing; the session uses {@link SummarizingCompactor} bound to the
 *       same model as the conversation.
 *   <li><b>BYO compactor</b> — pass a custom {@code ContextCompactor} to {@code
 *       SessionOptions.Builder.withContextCompactor(...)}. To see the history before and after a
 *       compaction, wrap the default one: delegate to it and inspect both sides; {@code
 *       QueryEvent.ContextEdited} reports the removed-block and token counts.
 *   <li><b>BYO hook</b> — register a {@code PreModelTurnHook} returning {@code
 *       HookOutcome.MutateHistory} carrying the rewritten history. Lets the user trim without
 *       implementing the full SPI; e.g. a "drop tool-result rows older than turn N" policy.
 * </ol>
 *
 * <h2>Thread-safety</h2>
 *
 * Implementations are called from the single-virtual-thread agent loop; concurrent invocations on
 * one instance do not occur.
 */
@FunctionalInterface
public interface ContextCompactor {

  /**
   * Produce a shorter history from the given one. Implementations that invoke a model for
   * summarisation report the model call's {@code Usage} on the returned {@link CompactionResult} so
   * the agent loop can accumulate it into the session totals and apply the configured cost
   * calculator, and report a failed model call as the result's {@link CompactionResult#error()}.
   * Pure-trim implementations return {@link CompactionResult#noOp(List)}.
   *
   * @param history the current conversation history; non-null, may be empty
   * @param context the session's id, current turn index, cancellation token and model, as hooks get
   *     them at this turn; non-null
   * @return the result carrying the new history + usage consumed; non-null. The result's history
   *     may be the supplied {@code history} unchanged when nothing was dropped — the loop detects
   *     the no-shrink and does not emit {@code ContextEdited}
   */
  CompactionResult compact(List<Message> history, HookContext context);

  /**
   * A compactor that leaves the history unchanged. The agent loop still emits {@code
   * ContextWarning} at the 0.85 watermark and still calls this compactor at 0.95, which returns the
   * history as it was, so no {@code ContextEdited} fires; a long session overflows the model's
   * window and the provider's error ends it. Useful for tests and for deployments that would rather
   * fail on overflow than summarise, or that rewrite history in a {@code PreModelTurnHook}.
   *
   * @return a thread-safe compactor that never compacts; never null
   */
  static ContextCompactor disabled() {
    return (history, context) -> CompactionResult.noOp(history);
  }
}
