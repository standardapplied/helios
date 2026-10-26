/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.session.hooks.HookOutcome;

/**
 * Fires the turn-level hooks around the model call — {@code PreModelTurn} and {@code PostModelTurn}
 * — and applies their outcome to the session, telling the {@link TurnRunner} whether to keep going.
 *
 * <p>{@link HookOutcome.MutateHistory} at PreModelTurn rewrites the conversation history wholesale.
 */
final class TurnHooks {

  /** Whether the turn continues after a turn-level hook. */
  enum Decision {
    CONTINUE,
    SKIP_MODEL,
    TERMINATE
  }

  private static final String PRE_MODEL_TURN = "PreModelTurnHook";
  private static final String POST_MODEL_TURN = "PostModelTurnHook";

  private final LoopCollaborators collaborators;
  private final EventEmitter emitter;
  private final HookEffects effects;

  TurnHooks(LoopCollaborators collaborators, EventEmitter emitter, HookEffects effects) {
    this.collaborators = collaborators;
    this.emitter = emitter;
    this.effects = effects;
  }

  /** Fire {@code PreModelTurn} against the current history and apply its outcome. */
  Decision beforeModelTurn(SessionState state) {
    var decision =
        collaborators
            .hooks()
            .firePreModelTurn(state.history().snapshot(), collaborators.hookContext(state));
    var hookName = decision.firingHookName();
    return switch (decision.outcome()) {
      case HookOutcome.Stop stop -> stop(state, hookName, PRE_MODEL_TURN, stop);
      case HookOutcome.Inject inject -> {
        effects.inject(state, hookName, PRE_MODEL_TURN, inject);
        yield Decision.SKIP_MODEL;
      }
      case HookOutcome.MutateHistory mutate -> {
        state.history().replace(mutate.history());
        emitter.emitHookFired(state, hookName, PRE_MODEL_TURN, "MutateHistory");
        yield Decision.CONTINUE;
      }
      case HookOutcome.Continue _ -> Decision.CONTINUE;
    };
  }

  /** Fire {@code PostModelTurn} against the turn's response and apply its outcome. */
  Decision afterModelTurn(SessionState state, Response<?> response) {
    var decision =
        collaborators.hooks().firePostModelTurn(response, collaborators.hookContext(state));
    var hookName = decision.firingHookName();
    return switch (decision.outcome()) {
      case HookOutcome.Stop stop -> stop(state, hookName, POST_MODEL_TURN, stop);
      case HookOutcome.Inject inject -> {
        effects.inject(state, hookName, POST_MODEL_TURN, inject);
        yield Decision.CONTINUE;
      }
      case HookOutcome.Continue _ -> Decision.CONTINUE;
    };
  }

  private Decision stop(SessionState state, String hookName, String phase, HookOutcome.Stop stop) {
    effects.stop(state, hookName, phase, stop);
    return Decision.TERMINATE;
  }
}
