/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.HookPhase;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The effects a hook's decision has on the session, the same at every phase that honours them: a
 * {@code Stop} ends the session with the hook's text and an {@code Inject} queues the hook's user
 * message, each announced by a {@link com.standardapplied.helios.session.QueryEvent.HookFired}.
 */
final class HookEffects {

  private static final Logger LOGGER = Logger.getLogger(TurnRunner.class.getName());

  private final EventEmitter emitter;
  private final SteeringQueue steeringQueue;

  HookEffects(EventEmitter emitter, SteeringQueue steeringQueue) {
    this.emitter = emitter;
    this.steeringQueue = steeringQueue;
  }

  /** Announce the {@code Stop} and record its {@link ResultMessage.Success} as the terminal. */
  void stop(SessionState state, String hookName, HookPhase phase, HookOutcome.Stop stop) {
    emitter.emitHookFired(state, hookName, phase, stop);
    state.setTerminal(StopClassifier.success(state, stop.result()));
  }

  /**
   * Announce the {@code Inject} and offer its message to the steering queue. Logs a WARNING if the
   * queue rejects it (e.g. full at capacity) — without this, hook authors believe their {@code
   * Inject} took effect but the loop never sees the message, producing impossible-to-debug
   * cascading bugs.
   */
  void inject(SessionState state, String hookName, HookPhase phase, HookOutcome.Inject inject) {
    emitter.emitHookFired(state, hookName, phase, inject);
    if (!steeringQueue.offer(UserMessage.text(inject.userMessage()))) {
      LOGGER.log(
          Level.WARNING,
          "{0} hook ''{1}'' Inject was dropped: steering queue full; session continues without"
              + " the injected message",
          new Object[] {phase, hookName});
    }
  }
}
