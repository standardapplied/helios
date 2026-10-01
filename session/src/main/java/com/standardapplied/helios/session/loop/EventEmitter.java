/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import ai.singlr.session.QueryEvent;
import ai.singlr.session.hooks.HookContext;
import ai.singlr.session.hooks.HookRegistry;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Glue between the agent loop's {@link QueryEvent} sink and its {@link HookRegistry}'s observe-only
 * {@link ai.singlr.session.hooks.OnStreamEventHook OnStreamEventHook} dispatch. The agent loop and
 * {@link TurnRunner} share one instance per session; every {@link #emit(SessionState, QueryEvent)}
 * call writes to the publisher and fires the stream-event hooks against the same context.
 *
 * <p>{@link #emitHookFired(SessionState, String, String, String)} is a convenience for the common
 * "a hook just decided something" event — it falls back to the phase name when the firing hook
 * didn't declare its own.
 *
 * <h2>Thread-safety</h2>
 *
 * Immutable. The supplied {@code eventSink}, {@code hooks}, {@code hookContextFactory}, and {@code
 * clock} are expected to be thread-safe by their owner; this class adds no synchronisation.
 */
final class EventEmitter {

  private final Consumer<QueryEvent> eventSink;
  private final HookRegistry hooks;
  private final Function<SessionState, HookContext> hookContextFactory;
  private final Clock clock;

  EventEmitter(
      Consumer<QueryEvent> eventSink,
      HookRegistry hooks,
      Function<SessionState, HookContext> hookContextFactory,
      Clock clock) {
    this.eventSink = Objects.requireNonNull(eventSink, "eventSink must not be null");
    this.hooks = Objects.requireNonNull(hooks, "hooks must not be null");
    this.hookContextFactory =
        Objects.requireNonNull(hookContextFactory, "hookContextFactory must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /** The clock backing this emitter — exposed for callers that build event timestamps inline. */
  Clock clock() {
    return clock;
  }

  /** Emit an event to the sink and fire observe-only stream-event hooks against it. */
  void emit(SessionState state, QueryEvent event) {
    eventSink.accept(event);
    hooks.fireOnStreamEvent(event, hookContextFactory.apply(state));
  }

  /**
   * Emit a {@link QueryEvent.HookFired} event. When {@code hookName} is {@code null} the phase name
   * is used as a fallback so the event is still meaningful.
   */
  void emitHookFired(SessionState state, String hookName, String phase, String outcomeKind) {
    emit(
        state,
        new QueryEvent.HookFired(
            state.sessionId(),
            state.currentTurnIndex(),
            clock.instant(),
            hookName == null ? phase : hookName,
            phase,
            outcomeKind));
  }
}
