/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.CancellationToken;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.test.MockModel;
import com.standardapplied.helios.core.tool.SessionContext;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.HookPhase;
import com.standardapplied.helios.session.hooks.PostModelTurnHook;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/**
 * The turn's stages at their edges: a {@code Stop} or a mutation after the model turn, an injected
 * message the full steering queue drops, and a pre-tool hook with no name.
 */
final class TurnStagesTest {

  private static final InstantSource CLOCK =
      InstantSource.fixed(Instant.parse("2026-05-14T19:00:00Z"));
  private final List<QueryEvent> events = new CopyOnWriteArrayList<>();
  private final SteeringQueue queue = new SteeringQueue(1);
  private final SessionState state =
      new SessionState("sess-stages", new CancellationToken(), CLOCK);

  private TurnRunner runner(Model model, Hook... hooks) {
    var dispatch =
        new ToolDispatch(
            SessionContext.forTesting("sess-stages"), ToolRegistry.empty(), state.ending());
    var collaborators =
        new LoopCollaborators(
            new HookRegistry(List.of(hooks), new SecretRegistry()),
            dispatch,
            Authorizations.ADMIT_ALL,
            Authorizations.NO_RULE,
            queue,
            events::add,
            TurnStagesTest::context,
            CLOCK);
    state.history().append(Message.user("hello"));
    state.beginTurn();
    return new TurnRunner(collaborators, model, CostCalculator.ZERO, null);
  }

  private static HookContext context(SessionState s) {
    return new DefaultHookContext(
        s.sessionId(), s.runId(), s.currentTurnIndex(), s.cancellation(), new MockModel("unused"));
  }

  @Test
  void postModelTurnStopEndsTheSessionWithTheHookText() {
    PostModelTurnHook stop = (response, ctx) -> HookOutcome.stop("halted after the turn");

    var outcome =
        runner(ScriptedModel.newBuilder().withTextTurn("hello back").build(), stop)
            .runTurn(state, SessionLimits.defaults());

    assertEquals(FinishReason.STOP, outcome.finishReason());
    assertEquals("", outcome.assistantContent());
    var success = assertInstanceOf(ResultMessage.Success.class, state.terminal().orElseThrow());
    assertEquals("halted after the turn", success.result());
  }

  @Test
  void injectIntoAFullSteeringQueueIsDroppedWithAWarningOnTheTurnRunnerLogger() {
    queue.offer(UserMessage.text("occupying"));
    var named =
        new PreModelTurnHook() {
          @Override
          public String name() {
            return "injector";
          }

          @Override
          public HookOutcome.PreModelTurn beforeModelTurn(List<Message> history, HookContext ctx) {
            return HookOutcome.inject("dropped");
          }
        };
    var records = new CopyOnWriteArrayList<LogRecord>();

    TurnOutcome outcome;
    try (var ignored = LogCapture.of(TurnRunner.class, records)) {
      outcome = runner(new MockModel("unused"), named).runTurn(state, SessionLimits.defaults());
    }

    assertEquals(FinishReason.TOOL_CALLS, outcome.finishReason());
    assertEquals(List.of("occupying"), queue.drain().stream().map(UserMessage::text).toList());
    var warning = records.getFirst();
    assertEquals(Level.WARNING, warning.getLevel());
    assertEquals(TurnRunner.class.getName(), warning.getLoggerName());
    assertEquals(
        "{0} hook ''{1}'' Inject was dropped: steering queue full; session continues without"
            + " the injected message",
        warning.getMessage());
    assertEquals(List.of(HookPhase.PRE_MODEL_TURN, "injector"), List.of(warning.getParameters()));
  }
}
