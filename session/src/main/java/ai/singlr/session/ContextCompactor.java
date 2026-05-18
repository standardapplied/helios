/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.model.Message;
import ai.singlr.session.loop.SessionState;
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
 * <p>The default ships {@link DropMiddleToolResultsCompactor}: preserve the system prompt + first
 * user turn, preserve the most recent trajectory, summarise the middle via a single model call.
 *
 * <h2>Three tiers of library-user control</h2>
 *
 * <ol>
 *   <li><b>Default</b> — do nothing; the session uses {@link DropMiddleToolResultsCompactor} bound
 *       to the same model as the conversation.
 *   <li><b>BYO compactor</b> — pass a custom {@code ContextCompactor} to {@code
 *       SessionOptions.Builder.withContextCompactor(...)}.
 *   <li><b>BYO hook</b> — register a {@code PreModelTurnHook} returning {@code
 *       HookOutcome.MutateInput} carrying the rewritten history under the key {@code "history"}.
 *       Lets the user trim without implementing the full SPI; e.g. a "drop tool-result rows older
 *       than turn N" policy.
 * </ol>
 *
 * <h2>Thread-safety</h2>
 *
 * Implementations are called from the single-virtual-thread agent loop; concurrent invocations on
 * one instance do not occur. {@code SessionState} is mutated by the loop and may be read inside
 * {@code compact} without external synchronisation.
 */
@FunctionalInterface
public interface ContextCompactor {

  /**
   * Produce a shorter history from the given one. Implementations that invoke a model for
   * summarisation report the model call's {@code Usage} on the returned {@link CompactionResult} so
   * the agent loop can accumulate it into the session totals and apply the configured cost
   * calculator. Pure-trim implementations return {@link CompactionResult#noOp(List)} or {@code new
   * CompactionResult(history, Usage.of(0, 0))}.
   *
   * @param history the current conversation history; non-null, may be empty
   * @param state the per-session state; non-null. Carries the running {@code sessionId}, {@code
   *     currentTurnIndex}, accumulated {@code usage}, etc.
   * @return the result carrying the new history + usage consumed; non-null. The result's history
   *     may be the supplied {@code history} unchanged when nothing was dropped — the loop detects
   *     the no-shrink and does not emit {@code ContextEdited}
   */
  CompactionResult compact(List<Message> history, SessionState state);

  /**
   * A no-op compactor. The agent loop will still emit {@code ContextWarning} at the 0.85 watermark
   * but will not invoke a compactor at 0.95 — sessions overflow naturally and surface {@code
   * ErrorDuringExecution} via the provider. Useful for tests, demos, and deployments that
   * intentionally want to fail-loud on overflow rather than introduce summarisation drift.
   *
   * @return a shared, thread-safe disabled compactor; never null
   */
  static ContextCompactor disabled() {
    return DisabledContextCompactor.INSTANCE;
  }
}
