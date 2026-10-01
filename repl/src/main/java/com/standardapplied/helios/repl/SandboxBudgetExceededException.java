/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

/**
 * Thrown by the budget-checking wrapper around a host function when a session has used more of a
 * budgeted resource than {@link ReplConfig} allows. The exception propagates through the JSON-RPC
 * bridge as a JShell-side error in the next {@code execute_code} tool result, so the model sees a
 * structured budget message and can wrap up gracefully (typically by calling {@code submit()} with
 * whatever it has gathered so far) instead of continuing to spend.
 *
 * <p>Defense in depth against the paper's "thousands of recursive sub-calls on simple tasks"
 * failure mode (Appendix B.3). Trampoline ships exactly one budget — total LLM calls per session —
 * and so do we.
 */
public final class SandboxBudgetExceededException extends RuntimeException {

  /** Which budget category was tripped. Today: just {@link #LLM_CALLS}. */
  public enum BudgetKind {
    /** Total LLM calls (cumulative {@code predict} invocations) per session. */
    LLM_CALLS
  }

  private final BudgetKind kind;
  private final long limit;
  private final long actual;

  public SandboxBudgetExceededException(BudgetKind kind, long limit, long actual, String message) {
    super(message);
    this.kind = kind;
    this.limit = limit;
    this.actual = actual;
  }

  public BudgetKind kind() {
    return kind;
  }

  public long limit() {
    return limit;
  }

  public long actual() {
    return actual;
  }
}
