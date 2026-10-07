/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.session.ConcurrencyLimits;
import com.standardapplied.helios.session.ContextCompactor;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookRegistry;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Wires the collaborators of a {@link TurnRunner} or {@link AgentLoop} under test: a tool dispatch
 * over a given registry, a hook-context factory over a model no hook calls, a clock fixed at one
 * instant, a scheduler the test closes, and an event sink that records into {@link #events}.
 */
final class LoopFixture implements AutoCloseable {

  final List<QueryEvent> events = new ArrayList<>();
  final InstantSource clock;
  private final String sessionId;
  private final Model hookModel = ScriptedModel.newBuilder().build();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  LoopFixture(String sessionId, Instant now) {
    this.sessionId = sessionId;
    this.clock = InstantSource.fixed(now);
  }

  /** A tool dispatch over {@code tools}, with the default concurrency limits. */
  ToolDispatch dispatch(ToolRegistry tools) {
    return new ToolDispatch(
        SessionContext.forTesting(sessionId), tools, ConcurrencyLimits.defaults());
  }

  /** The hook context of {@code state}'s current turn. */
  HookContext hookContext(SessionState state) {
    return new DefaultHookContext(
        state.sessionId(), state.currentTurnIndex(), state.cancellation(), hookModel);
  }

  /** Collaborators that emit into {@link #events}, on this fixture's clock. */
  LoopCollaborators collaborators(HookRegistry hooks, ToolDispatch dispatch, SteeringQueue queue) {
    return new LoopCollaborators(hooks, dispatch, queue, events::add, this::hookContext, clock);
  }

  /** A runner of {@code model} with no costs and no output schema. */
  TurnRunner runner(LoopCollaborators collaborators, Model model) {
    return runner(collaborators, model, CostCalculator.ZERO, null);
  }

  /** A runner of {@code model} pricing turns with {@code costs}, typed when a schema is given. */
  TurnRunner runner(
      LoopCollaborators collaborators,
      Model model,
      CostCalculator costs,
      OutputSchema<?> outputSchema) {
    return new TurnRunner(collaborators, model, costs, outputSchema, scheduler);
  }

  /** A loop over {@link #runner(LoopCollaborators, Model) a plain runner} of {@code model}. */
  AgentLoop loop(
      LoopCollaborators collaborators,
      Model model,
      TokenCounter counter,
      ContextCompactor compactor) {
    return loop(collaborators, runner(collaborators, model), counter, compactor);
  }

  /** A loop over {@code runner} with the standard stop classifier. */
  AgentLoop loop(
      LoopCollaborators collaborators,
      TurnRunner runner,
      TokenCounter counter,
      ContextCompactor compactor) {
    return new AgentLoop(collaborators, runner, new StopClassifier(), counter, compactor);
  }

  /** A fresh state of this fixture's session, cancelled through {@code cancellation}. */
  SessionState state(CancellationToken cancellation) {
    return new SessionState(sessionId, cancellation, clock);
  }

  /** A fresh state of this fixture's session with its own cancellation token. */
  SessionState state() {
    return state(new CancellationToken());
  }

  /** The scheduler every runner of this fixture uses, shut down by {@link #close}. */
  ScheduledExecutorService scheduler() {
    return scheduler;
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }
}
