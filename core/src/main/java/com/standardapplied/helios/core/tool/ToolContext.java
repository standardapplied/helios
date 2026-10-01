/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.tool;

import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.runtime.SessionContext;
import java.time.Duration;
import java.util.Objects;

/**
 * Per-invocation context handed to a {@link ToolExecutor}. Carries the {@link SessionContext} for
 * the in-flight session (session id, the session's {@link CancellationToken}, the session's clock),
 * plus the per-call {@code deadline} the dispatcher will enforce as a wall-clock timeout.
 *
 * <p>The dispatcher is responsible for the hard timeout (it runs the executor on a virtual thread
 * and abandons it after {@code deadline}). Tools that observe {@link #deadline()} can shape their
 * work — e.g. stop a tree walk early — but they do not need to enforce it; missing the deadline is
 * not a correctness bug, only an efficiency one.
 *
 * <p>Use {@link #noop()} for call sites that don't have a real session (tests, direct library use).
 * The noop session id is the fixed string {@code "noop-session"} so providers that key by session
 * id behave deterministically in tests.
 *
 * <h2>Thread-safety</h2>
 *
 * Immutable. The wrapped {@link SessionContext} is itself immutable; its {@link CancellationToken}
 * is thread-safe.
 *
 * @param sessionContext per-session metadata; non-null
 * @param deadline the wall-clock window the dispatcher will allow before abandoning the executor;
 *     non-null and non-negative
 */
public record ToolContext(SessionContext sessionContext, Duration deadline) {

  private static final ToolContext NOOP =
      new ToolContext(SessionContext.forTesting("noop-session"), Duration.ofDays(1));

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code sessionContext} or {@code deadline} is null
   * @throws IllegalArgumentException if {@code deadline} is negative
   */
  public ToolContext {
    Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    Objects.requireNonNull(deadline, "deadline must not be null");
    if (deadline.isNegative()) {
      throw new IllegalArgumentException("deadline must not be negative, got " + deadline);
    }
  }

  /**
   * The shared "no real context" instance — fresh never-cancelled token, system clock, generous
   * deadline. Use for tests and direct library callers that don't have a session.
   *
   * @return a stable noop context
   */
  public static ToolContext noop() {
    return NOOP;
  }

  /**
   * Build a context with the given session context and deadline.
   *
   * @param sessionContext non-null session metadata
   * @param deadline non-null non-negative deadline
   * @return a fresh context
   */
  public static ToolContext of(SessionContext sessionContext, Duration deadline) {
    return new ToolContext(sessionContext, deadline);
  }

  /**
   * The session's cancellation token. Convenience accessor — equivalent to {@code
   * sessionContext().cancellation()}.
   *
   * @return the session's cancellation token
   */
  public CancellationToken cancellation() {
    return sessionContext.cancellation();
  }
}
