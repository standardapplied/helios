/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.core.tool;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import java.time.Duration;
import java.util.Objects;

/**
 * Per-invocation context handed to a {@link ToolExecutor}. Carries the {@link SessionContext} for
 * the in-flight session (session id, the session's {@link CancellationToken}, the session's clock),
 * the call's own cancellation token, and the per-call {@code deadline} the dispatcher enforces.
 *
 * <p>{@link #cancellation()} is the call's token, not the session's: the session dispatcher cancels
 * it when the call times out, and the session's cancellation reaches it too. A tool polls it at
 * safe points and hands it to whatever it waits on. Session-scoped cleanup registers against {@code
 * sessionContext().cancellation()} instead, which one call's timeout never cancels.
 *
 * <p>The dispatcher is responsible for the hard timeout. Tools that observe {@link #deadline()} can
 * shape their work — e.g. stop a tree walk early — but they do not need to enforce it; missing the
 * deadline is not a correctness bug, only an efficiency one.
 *
 * <p>Use {@link #noop()} for call sites that don't have a real session (tests, direct library use).
 * The noop session id is the fixed string {@code "noop-session"} so providers that key by session
 * id behave deterministically in tests.
 *
 * <h2>Thread-safety</h2>
 *
 * Immutable. The wrapped {@link SessionContext} is itself immutable; both {@link
 * CancellationToken}s are thread-safe.
 *
 * @param sessionContext per-session metadata; non-null
 * @param cancellation the call's cancellation token; non-null
 * @param deadline the window the dispatcher allows the call before cancelling it; non-null and
 *     non-negative
 */
public record ToolContext(
    SessionContext sessionContext, CancellationToken cancellation, Duration deadline) {

  private static final SessionContext NOOP_SESSION = SessionContext.forTesting("noop-session");
  private static final ToolContext NOOP =
      new ToolContext(NOOP_SESSION, NOOP_SESSION.cancellation(), Duration.ofDays(1));

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code deadline} is negative
   */
  public ToolContext {
    Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    Objects.requireNonNull(cancellation, "cancellation must not be null");
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
}
