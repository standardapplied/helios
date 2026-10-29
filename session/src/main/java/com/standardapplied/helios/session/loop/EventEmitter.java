/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.HookPhase;
import java.time.InstantSource;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Glue between the agent loop's {@link QueryEvent} sink and its {@link HookRegistry}'s observe-only
 * {@link com.standardapplied.helios.session.hooks.OnStreamEventHook OnStreamEventHook} dispatch.
 * The agent loop and {@link TurnRunner} share one instance per session; every {@link
 * #emit(SessionState, QueryEvent)} call writes to the event sink and fires the stream-event hooks
 * against the same context.
 *
 * <p>{@link #emitHookFired(SessionState, String, HookPhase, HookOutcome)} is a convenience for the
 * common "a hook just decided something" event; it publishes the outcome redacted against the
 * session's secrets.
 *
 * <h2>Thread-safety</h2>
 *
 * Immutable. The supplied {@code eventSink}, {@code hooks}, {@code hookContextFactory}, {@code
 * clock} and {@code secrets} are expected to be thread-safe by their owner; this class adds no
 * synchronisation.
 */
final class EventEmitter {

  private final Consumer<QueryEvent> eventSink;
  private final HookRegistry hooks;
  private final Function<SessionState, HookContext> hookContextFactory;
  private final InstantSource clock;
  private final SecretRegistry secrets;

  EventEmitter(
      Consumer<QueryEvent> eventSink,
      HookRegistry hooks,
      Function<SessionState, HookContext> hookContextFactory,
      InstantSource clock,
      SecretRegistry secrets) {
    this.eventSink = Objects.requireNonNull(eventSink, "eventSink must not be null");
    this.hooks = Objects.requireNonNull(hooks, "hooks must not be null");
    this.hookContextFactory =
        Objects.requireNonNull(hookContextFactory, "hookContextFactory must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.secrets = Objects.requireNonNull(secrets, "secrets must not be null");
  }

  /** The clock backing this emitter — exposed for callers that build event timestamps inline. */
  InstantSource clock() {
    return clock;
  }

  /** Emit an event to the sink and fire observe-only stream-event hooks against it. */
  void emit(SessionState state, QueryEvent event) {
    eventSink.accept(event);
    hooks.fireOnStreamEvent(event, hookContextFactory.apply(state));
  }

  /**
   * Emit a {@link QueryEvent.HookFired} event naming the hook that decided and carrying a redacted
   * copy of its outcome.
   */
  void emitHookFired(SessionState state, String hookName, HookPhase phase, HookOutcome outcome) {
    emit(
        state,
        new QueryEvent.HookFired(
            state.sessionId(),
            state.runId(),
            state.currentTurnIndex(),
            clock.instant(),
            hookName,
            phase,
            RedactedHookOutcome.of(outcome, secrets)));
  }
}
