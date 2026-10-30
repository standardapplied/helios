/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.core.tool;

import com.standardapplied.helios.core.model.CancellationToken;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

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
 * <p>{@link #withholds(String)} says whether the session's policy withholds an entry a tool walks
 * to below its authorised path, by the entry's canonical permission key; a tool that walks a
 * subtree skips such an entry.
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
 * @param withheld true for the canonical key of an entry the call must not return; non-null
 */
public record ToolContext(
    SessionContext sessionContext,
    CancellationToken cancellation,
    Duration deadline,
    Predicate<String> withheld) {

  private static final SessionContext NOOP_SESSION = SessionContext.forTesting("noop-session");
  private static final ToolContext NOOP =
      new ToolContext(NOOP_SESSION, NOOP_SESSION.cancellation(), Duration.ofDays(1), key -> false);

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
    Objects.requireNonNull(withheld, "withheld must not be null");
  }

  /**
   * Whether the call must not return the entry with canonical key {@code canonicalKey}.
   *
   * @param canonicalKey the entry's canonical permission key; non-null
   * @return {@code true} when the session's policy withholds it
   */
  public boolean withholds(String canonicalKey) {
    return withheld.test(canonicalKey);
  }

  /**
   * The shared "no real context" instance — fresh never-cancelled token, system clock, generous
   * deadline, nothing withheld. Use for tests and direct library callers that don't have a session.
   *
   * @return a stable noop context
   */
  public static ToolContext noop() {
    return NOOP;
  }
}
