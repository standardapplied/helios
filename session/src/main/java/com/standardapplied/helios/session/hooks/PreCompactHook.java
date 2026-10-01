/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import ai.singlr.core.model.Message;
import java.util.List;

/**
 * Hook fired just before the agent loop hands history to its configured {@code ContextCompactor}.
 * The standard escape hatch for callers that want to archive the full transcript before any
 * summarisation, or substitute a pre-trimmed history for the compactor to operate on.
 *
 * <p>Outcome semantics:
 *
 * <ul>
 *   <li>{@link HookOutcome.Continue Continue} — the compactor runs against the unmodified history
 *       (the default and the only meaningful "do nothing" answer).
 *   <li>{@link HookOutcome.MutateHistory MutateHistory} — carries the rewritten history (a {@code
 *       List<Message>}). The compactor receives this in place of the loop's current snapshot. Use
 *       case: drop oversize tool-result rows before the summary model sees them, or hand off a
 *       curated subset that's already been filtered by an external relevance scorer.
 *   <li>{@link HookOutcome.Block Block}, {@link HookOutcome.Inject Inject}, {@link HookOutcome.Stop
 *       Stop} — not meaningful at this phase. Returning any of these is logged at {@code WARNING}
 *       and treated as {@link HookOutcome.Continue Continue}; the compactor still runs against the
 *       unmodified history. Callers that need to veto compaction entirely should register a {@code
 *       ContextCompactor} of their own (or wire {@link
 *       ai.singlr.session.ContextCompactor#disabled() ContextCompactor.disabled()}).
 * </ul>
 */
@FunctionalInterface
public non-sealed interface PreCompactHook extends Hook {

  /**
   * Inspect (and optionally rewrite) the history the loop is about to compact.
   *
   * @param history the history about to be sent to the compactor; non-null, immutable
   * @param ctx the per-invocation context; non-null
   * @return the hook's decision
   */
  HookOutcome beforeCompact(List<Message> history, HookContext ctx);

  @Override
  default String name() {
    return getClass().getSimpleName();
  }
}
