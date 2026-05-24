/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import ai.singlr.core.common.Strings;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of an {@link ExecutionProvider#onSessionStart} call. The provider either accepts the
 * session (returns {@link #accept()}) or refuses it with a reason (returns {@link #refuse(String)
 * Refuse}).
 *
 * <p>A {@link Refuse} outcome short-circuits session construction — the agent loop never starts;
 * the session's terminal becomes {@link ai.singlr.session.ResultMessage.ErrorProviderUnavailable}
 * carrying the reason. This is the canonical signal for "provider is saturated" (warm pool
 * exhausted), "auth failed for this session" (per-session API keys), or "this provider does not
 * accept new work right now" (in-flight close).
 *
 * <p>Mirrors the {@link ai.singlr.session.hooks.HookOutcome} pattern — sealed sum type that
 * pattern-matches cleanly inside the session loop.
 *
 * <p>Spec: §11.2.
 */
public sealed interface SessionStartOutcome
    permits SessionStartOutcome.Accept, SessionStartOutcome.Refuse {

  /** Singleton {@link Accept} returned for the common "session accepted" case. */
  Accept ACCEPT = new Accept();

  /**
   * The provider accepts this session. No further data needed.
   *
   * @return the singleton {@link Accept}
   */
  static Accept accept() {
    return ACCEPT;
  }

  /**
   * The provider refuses this session with a reason and no underlying cause.
   *
   * @param reason a human-readable explanation surfaced through the resulting {@code
   *     ErrorProviderUnavailable}; non-blank
   * @return a fresh {@link Refuse}
   * @throws NullPointerException if {@code reason} is null
   * @throws IllegalArgumentException if {@code reason} is blank
   */
  static Refuse refuse(String reason) {
    return new Refuse(reason, null);
  }

  /**
   * The provider refuses this session with a reason and the underlying cause that triggered the
   * refusal. Use this overload whenever the refusal is the result of a caught exception so
   * deployers retain typed access to the original {@code Throwable} — its class, message, cause
   * chain, and full stack trace — without parsing strings. Telemetry pipelines, structured loggers,
   * and retry classifiers all need the typed cause to make decisions; collapsing it into the reason
   * string at the refusal site discards information that callers cannot recover.
   *
   * @param reason a human-readable explanation; non-blank
   * @param cause the underlying throwable that triggered the refusal; may be {@code null} if no
   *     exception was caught
   * @return a fresh {@link Refuse} carrying the cause
   * @throws NullPointerException if {@code reason} is null
   * @throws IllegalArgumentException if {@code reason} is blank
   */
  static Refuse refuse(String reason, Throwable cause) {
    return new Refuse(reason, cause);
  }

  /** The provider accepts the session. */
  record Accept() implements SessionStartOutcome {}

  /**
   * The provider refuses the session. The {@code reason} flows into the session's terminal so
   * callers can surface it without parsing a stack trace; the optional {@code cause} preserves the
   * underlying throwable for callers that want to inspect or log the original failure.
   *
   * @param reason non-blank explanation
   * @param cause the underlying throwable that triggered the refusal, or {@code null} when the
   *     refusal is a deliberate policy decision (saturation, auth) rather than a caught failure
   */
  record Refuse(String reason, Throwable cause) implements SessionStartOutcome {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException if {@code reason} is null
     * @throws IllegalArgumentException if {@code reason} is blank
     */
    public Refuse {
      Objects.requireNonNull(reason, "reason must not be null");
      if (Strings.isBlank(reason)) {
        throw new IllegalArgumentException("reason must not be blank");
      }
    }

    /**
     * The underlying throwable that triggered this refusal, if any.
     *
     * @return the cause, or empty if the refusal was a policy decision with no exception
     */
    public Optional<Throwable> causeOpt() {
      return Optional.ofNullable(cause);
    }
  }
}
