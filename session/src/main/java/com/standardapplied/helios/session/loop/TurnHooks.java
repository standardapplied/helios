/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.session.hooks.HookDecision;
import com.standardapplied.helios.session.hooks.HookOutcome;

/**
 * Fires the turn-level hooks around the model call — {@code PreModelTurn} and {@code PostModelTurn}
 * — and applies their outcome to the session, telling the {@link TurnRunner} whether to keep going.
 *
 * <p>{@link HookOutcome.MutateHistory} at PreModelTurn rewrites the conversation history wholesale
 * — the BYO-compactor-as-hook path. At PostModelTurn, mutation variants are not meaningful and are
 * treated as Continue.
 */
final class TurnHooks {

  /** Whether the turn continues after a turn-level hook. */
  enum Decision {
    CONTINUE,
    SKIP_MODEL,
    TERMINATE
  }

  /**
   * Typed tag for the phase whose decision is applied. Avoids a stringly-typed {@code
   * phaseName.equals("PreModelTurnHook")} comparison.
   */
  private enum Phase {
    PRE_MODEL_TURN("PreModelTurnHook"),
    POST_MODEL_TURN("PostModelTurnHook");

    private final String phaseName;

    Phase(String phaseName) {
      this.phaseName = phaseName;
    }
  }

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
    return apply(state, decision, Phase.PRE_MODEL_TURN);
  }

  /** Fire {@code PostModelTurn} against the turn's response and apply its outcome. */
  Decision afterModelTurn(SessionState state, Response<?> response) {
    var decision =
        collaborators.hooks().firePostModelTurn(response, collaborators.hookContext(state));
    return apply(state, decision, Phase.POST_MODEL_TURN);
  }

  private Decision apply(SessionState state, HookDecision decision, Phase phase) {
    var hookName = HookEffects.firingHookName(decision);
    return switch (decision.outcome()) {
      case HookOutcome.Stop stop -> {
        effects.stop(state, hookName, phase.phaseName, stop);
        yield Decision.TERMINATE;
      }
      case HookOutcome.Inject inject -> {
        effects.inject(state, hookName, phase.phaseName, inject);
        yield phase == Phase.PRE_MODEL_TURN ? Decision.SKIP_MODEL : Decision.CONTINUE;
      }
      case HookOutcome.MutateHistory mutate -> mutateHistory(state, hookName, phase, mutate);
      default -> Decision.CONTINUE;
    };
  }

  private Decision mutateHistory(
      SessionState state, String hookName, Phase phase, HookOutcome.MutateHistory mutate) {
    if (phase == Phase.PRE_MODEL_TURN) {
      state.history().replace(mutate.history());
      state.contextWatermark().reset();
      emitter.emitHookFired(state, hookName, phase.phaseName, "MutateHistory");
    }
    return Decision.CONTINUE;
  }
}
