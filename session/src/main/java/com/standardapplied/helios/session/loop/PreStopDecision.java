/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.hooks.HookOutcome;
import java.util.Optional;

/**
 * Applies the pre-stop hooks to the {@link StopClassifier}'s terminal verdict. Only a {@link
 * ResultMessage.Success} goes through them; other terminals (cancel, max-turns, errors) bypass.
 * {@link HookOutcome.Inject Inject} reverses the stop and queues a synthetic message; {@link
 * HookOutcome.Stop Stop} overrides the result text; Continue and Block confirm the stop unchanged;
 * mutation variants are not meaningful and are treated as Continue.
 */
final class PreStopDecision {

  private static final String PHASE = "PreStopHook";

  private final LoopCollaborators collaborators;
  private final EventEmitter emitter;
  private final HookEffects effects;

  PreStopDecision(LoopCollaborators collaborators, EventEmitter emitter) {
    this.collaborators = collaborators;
    this.emitter = emitter;
    this.effects = new HookEffects(emitter, collaborators.steeringQueue());
  }

  /**
   * Resolve the classifier's verdict.
   *
   * @return the terminal to end with, or empty when a hook declined to stop
   */
  Optional<ResultMessage> resolve(
      SessionState state, ResultMessage classifierVerdict, TurnOutcome outcome) {
    if (!(classifierVerdict instanceof ResultMessage.Success)) {
      return Optional.of(classifierVerdict);
    }
    var response =
        Response.newBuilder()
            .withContent(outcome.assistantContent())
            .withFinishReason(outcome.finishReason())
            .withUsage(outcome.usage())
            .build();
    var decision = collaborators.hooks().firePreStop(response, collaborators.hookContext(state));
    var hookName = decision.firingHookName();
    return switch (decision.outcome()) {
      case HookOutcome.Stop stop -> {
        emitter.emitHookFired(state, hookName, PHASE, "Stop");
        yield Optional.of(StopClassifier.success(state, stop.result()));
      }
      case HookOutcome.Inject inject -> {
        effects.inject(state, hookName, PHASE, inject);
        yield Optional.empty();
      }
      default -> Optional.of(classifierVerdict);
    };
  }
}
