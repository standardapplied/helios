/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.MockModel;
import com.standardapplied.helios.session.ConcurrencyLimits;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.HookRegistry;
import com.standardapplied.helios.session.hooks.PostModelTurnHook;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The turn's stages at their edges: a {@code Stop} or a mutation after the model turn, an injected
 * message the full steering queue drops, and a pre-tool hook with no name.
 */
final class TurnStagesTest {

  private static final InstantSource CLOCK =
      InstantSource.fixed(Instant.parse("2026-05-14T19:00:00Z"));
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final List<QueryEvent> events = new CopyOnWriteArrayList<>();
  private final SteeringQueue queue = new SteeringQueue(1);
  private final SessionState state =
      new SessionState("sess-stages", new CancellationToken(), CLOCK);

  @AfterEach
  void shutDownScheduler() {
    scheduler.shutdownNow();
  }

  private TurnRunner runner(Model model, Hook... hooks) {
    var dispatch =
        new ToolDispatch(
            SessionContext.forTesting("sess-stages"),
            ToolRegistry.empty(),
            ConcurrencyLimits.defaults());
    var collaborators =
        new LoopCollaborators(
            new HookRegistry(List.of(hooks)),
            dispatch,
            queue,
            events::add,
            TurnStagesTest::context,
            CLOCK);
    state.history().append(Message.user("hello"));
    state.beginTurn();
    return new TurnRunner(collaborators, model, CostCalculator.ZERO, null, scheduler);
  }

  private static HookContext context(SessionState s) {
    return new DefaultHookContext(
        s.sessionId(), s.currentTurnIndex(), s.cancellation(), new MockModel("unused"));
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
  void postModelTurnMutateHistoryIsIgnored() {
    PostModelTurnHook mutate = (response, ctx) -> HookOutcome.mutateHistory(List.of());

    runner(ScriptedModel.newBuilder().withTextTurn("hello back").build(), mutate)
        .runTurn(state, SessionLimits.defaults());

    assertEquals(2, state.history().snapshot().size());
    assertTrue(events.stream().noneMatch(e -> e instanceof QueryEvent.HookFired));
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
          public HookOutcome beforeModelTurn(List<Message> history, HookContext ctx) {
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
    assertEquals(List.of("PreModelTurnHook", "injector"), List.of(warning.getParameters()));
  }

  @Test
  void aPreToolHookWithoutANameIsReportedUnderThePhaseName() {
    var unnamed =
        new PreToolUseHook() {
          @Override
          public String name() {
            return null;
          }

          @Override
          public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
            return call.id().equals("c1")
                ? HookOutcome.block("no")
                : HookOutcome.mutateArgs(Map.of("v", "mutated"));
          }
        };
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(
                new ToolCall("c1", "echo", Map.of()), new ToolCall("c2", "echo", Map.of()))
            .build();

    runner(model, unnamed).runTurn(state, SessionLimits.defaults());

    var blocked = only(QueryEvent.ToolBlocked.class);
    var mutated = only(QueryEvent.ToolMutated.class);
    assertEquals("PreToolUseHook", blocked.hookName());
    assertEquals("PreToolUseHook", mutated.hookName());
  }

  private <T extends QueryEvent> T only(Class<T> type) {
    var matching = events.stream().filter(type::isInstance).map(type::cast).toList();
    assertEquals(1, matching.size(), type.getSimpleName());
    return matching.getFirst();
  }
}
