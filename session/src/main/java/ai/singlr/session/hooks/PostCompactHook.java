/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

/**
 * Hook fired immediately after the agent loop swaps in a successfully shrunk history but before it
 * emits the {@code QueryEvent.ContextEdited} event. The standard auditing seam for callers that
 * want to log what was dropped, archive the produced summary, or verify a downstream invariant.
 *
 * <p>Outcome semantics:
 *
 * <ul>
 *   <li>{@link HookOutcome.Continue Continue} — observe-only acceptance (the default).
 *   <li>{@link HookOutcome.Stop Stop} — terminate the session before {@code ContextEdited} fires.
 *       Use case: the compaction violated a domain invariant (e.g. summary text mentions a redacted
 *       secret) and the session must not continue with the truncated history.
 *   <li>{@link HookOutcome.MutateInput MutateInput}, {@link HookOutcome.Block Block}, {@link
 *       HookOutcome.Inject Inject} — not meaningful at this phase (the compaction has already
 *       landed). Returning any of these is logged at {@code WARNING} and treated as {@link
 *       HookOutcome.Continue Continue}.
 * </ul>
 *
 * <p>The hook does NOT fire when the compactor returned a no-shrink result (same identity or {@code
 * historyAfter.size() >= historyBefore.size()}). Those are dispatched as a no-op and leave the
 * warning flag set so the next iteration can try compaction again.
 */
@FunctionalInterface
public non-sealed interface PostCompactHook extends Hook {

  /**
   * React to a completed compaction.
   *
   * @param payload the before/after snapshot of the compaction; non-null
   * @param ctx the per-invocation context; non-null
   * @return the hook's decision
   */
  HookOutcome afterCompact(CompactionPayload payload, HookContext ctx);

  @Override
  default String name() {
    return getClass().getSimpleName();
  }
}
