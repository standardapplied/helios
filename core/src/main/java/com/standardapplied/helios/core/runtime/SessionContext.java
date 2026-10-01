/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.runtime;

import ai.singlr.core.common.Strings;
import java.time.Clock;
import java.util.Objects;

/**
 * Per-session metadata threaded through dispatch surfaces — tool executors, execution providers,
 * lifecycle hooks. Carries the stable session identifier, the session's cooperative cancellation
 * token, and the clock the session uses for timestamps.
 *
 * <p>Lives in {@code core.runtime} (alongside {@link CancellationToken}) because it's needed by
 * both the session module (lifecycle hooks, agent loop) and the core tool surface ({@link
 * ai.singlr.core.tool.ToolContext}) — neither layer should depend on the other in either direction.
 *
 * <p>Implementations that pool per-session state (e.g. a JShell execution provider that maintains
 * one persistent REPL per session) key off {@link #sessionId()}. Implementations that need to
 * release resources on session shutdown register a {@link CancellationToken#onCancel} callback
 * against {@link #cancellation()} or implement the lifecycle hook surface.
 *
 * @param sessionId stable, non-blank session identifier; the same value carried on every {@link
 *     ai.singlr.core.tool.ToolContext} and {@code ResultMessage} produced by the session
 * @param cancellation the session-scoped cancellation token. Fires when the agent loop is asked to
 *     stop (user-initiated, timeout, budget) so per-session resources can clean up. Non-null
 * @param clock the clock the session uses for event timestamps and elapsed-time measurements. Tests
 *     pass a fixed clock for deterministic timing. Non-null
 */
public record SessionContext(String sessionId, CancellationToken cancellation, Clock clock) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code sessionId} is blank
   */
  public SessionContext {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (Strings.isBlank(sessionId)) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Build a context for tests or direct library use, with a fresh never-cancelled token and the
   * system UTC clock.
   *
   * @param sessionId non-blank session id
   * @return a fresh context
   * @throws NullPointerException if {@code sessionId} is null
   * @throws IllegalArgumentException if {@code sessionId} is blank
   */
  public static SessionContext forTesting(String sessionId) {
    return new SessionContext(sessionId, new CancellationToken(), Clock.systemUTC());
  }
}
