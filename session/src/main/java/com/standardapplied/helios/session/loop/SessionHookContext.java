/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.session.hooks.HookContext;

/**
 * The {@link HookContext} the loop hands its hooks and its compactor: the session's id, the current
 * turn, the session's token and model, and, for the session's own components only, its {@link
 * SessionEnding}, the sink of every {@link Error} caught on a thread the session runs work on. User
 * code cannot name this type, so it gets no route to the sink.
 *
 * @param sessionId the session's id
 * @param turnIndex the current turn's index
 * @param cancellation the session's token
 * @param model the session's model
 * @param ending the session's {@link Error} sink and cancellation path
 */
public record SessionHookContext(
    String sessionId,
    long turnIndex,
    CancellationToken cancellation,
    Model model,
    SessionEnding ending)
    implements HookContext {

  /** The context of {@code state}'s current turn in a session driving {@code model}. */
  public static SessionHookContext of(SessionState state, Model model) {
    return new SessionHookContext(
        state.sessionId(), state.currentTurnIndex(), state.cancellation(), model, state.ending());
  }
}
