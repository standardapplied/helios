/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.MockModel;
import com.standardapplied.helios.session.CompactionResult;
import com.standardapplied.helios.session.ContextCompactor;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.session.test.RedactedLogs;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/**
 * The loop's stages at their edges: a session already terminal, a compaction whose post-compact
 * hook stops the session before the turn, a throwing compactor, compact-hook outcomes their phase
 * does not honour, compaction spend reported as output tokens only, an on-user-message hook with no
 * name, and a pre-stop inject the full steering queue drops.
 */
final class LoopStagesTest {

  private static final InstantSource CLOCK =
      InstantSource.fixed(Instant.parse("2026-05-14T19:00:00Z"));
  private static final SessionLimits SMALL_WINDOW =
      SessionLimits.newBuilder().withMaxContextTokens(100).build();
  private static final TokenCounter OVER_THE_WATERMARK = messages -> 96L;

  private final List<QueryEvent> events = new CopyOnWriteArrayList<>();
  private final SteeringQueue queue = new SteeringQueue(1);
  private final SessionState state = new SessionState("sess-loop", new CancellationToken(), CLOCK);

  private AgentLoop loop(
      Model model, TokenCounter counter, ContextCompactor compactor, Hook... hooks) {
    var dispatch =
        new ToolDispatch(
            SessionContext.forTesting("sess-loop"), ToolRegistry.empty(), state.ending());
    var collaborators =
        new LoopCollaborators(
            new HookRegistry(List.of(hooks), new SecretRegistry()),
            dispatch,
            Authorizations.ADMIT_ALL,
            Authorizations.NO_RULE,
            queue,
            events::add,
            LoopStagesTest::context,
            CLOCK);
    var runner =
        new TurnRunner(
            collaborators,
            model,
            (modelId, usage) -> CostEstimate.ofMicroUsd(modelId.equals("summary") ? 7 : 1),
            null);
    return new AgentLoop(
        collaborators, runner, new StopClassifier(new SecretRegistry()), counter, compactor);
  }

  private AgentLoop loop(Model model, Hook... hooks) {
    return loop(model, TokenCounter.charBased(), ContextCompactor.disabled(), hooks);
  }

  private static HookContext context(SessionState s) {
    return new DefaultHookContext(
        s.sessionId(), s.currentTurnIndex(), s.cancellation(), new MockModel("unused"));
  }

  @Test
  void aSessionAlreadyTerminalEndsWithItsTerminal() {
    var cancelled =
        new ResultMessage.Cancelled(
            "sess-loop", "closed early", Usage.of(0, 0), CostEstimate.zero(), state.elapsed());
    state.setTerminal(cancelled);

    var result = loop(new MockModel("unused")).run(state, SessionLimits.defaults());

    assertSame(cancelled, result);
    var ended = assertInstanceOf(QueryEvent.LoopEnded.class, events.getLast());
    assertSame(cancelled, ended.result());
  }

  @Test
  void compactionSpendOfOutputTokensOnlyIsPricedAtTheCompactorModel() {
    state.history().append(Message.system("system"));
    queue.offer(UserMessage.text("hello"));
    var compacted = new AtomicBoolean();
    ContextCompactor summarise =
        (history, s) ->
            compacted.compareAndSet(false, true)
                ? new CompactionResult(List.of(history.getLast()), Usage.of(0, 5), "summary")
                : CompactionResult.noOp(history);
    var model = ScriptedModel.newBuilder().withTextTurn("done", Usage.of(2, 3)).build();

    loop(model, OVER_THE_WATERMARK, summarise).run(state, SMALL_WINDOW);

    assertEquals(Usage.of(2, 8), state.totals().usage());
    assertEquals(CostEstimate.ofMicroUsd(8), state.totals().cost());
  }

  @Test
  void aThrowingCompactorIsLoggedOnTheAgentLoopLoggerAndTheSessionContinues() {
    queue.offer(UserMessage.text("hello"));
    var failure = new IllegalStateException("compactor down");
    ContextCompactor throwing =
        (history, s) -> {
          throw failure;
        };
    var model = ScriptedModel.newBuilder().withTextTurn("done").build();
    var records = new CopyOnWriteArrayList<LogRecord>();

    ResultMessage result;
    try (var ignored = LogCapture.of(AgentLoop.class, records)) {
      result = loop(model, OVER_THE_WATERMARK, throwing).run(state, SMALL_WINDOW);
    }

    assertEquals("done", assertInstanceOf(ResultMessage.Success.class, result).result());
    var warning = records.getFirst();
    assertEquals(Level.WARNING, warning.getLevel());
    assertEquals(
        "context compactor threw; leaving history unchanged this turn", warning.getMessage());
    RedactedLogs.assertCopyOf(failure, warning.getThrown());
  }

  @Test
  void aPreStopInjectIntoAFullSteeringQueueIsDroppedWithAWarningOnTheAgentLoopLogger() {
    queue.offer(UserMessage.text("hello"));
    var fired = new AtomicBoolean();
    var filler =
        new PreStopHook() {
          @Override
          public String name() {
            return "filler";
          }

          @Override
          public HookOutcome.PreStop beforeStop(
              com.standardapplied.helios.core.model.Response<?> response, HookContext ctx) {
            if (!fired.compareAndSet(false, true)) {
              return HookOutcome.cont();
            }
            queue.offer(UserMessage.text("occupying"));
            return HookOutcome.inject("dropped");
          }
        };
    var model = ScriptedModel.newBuilder().withTextTurn("first").withTextTurn("second").build();
    var records = new CopyOnWriteArrayList<LogRecord>();

    ResultMessage result;
    try (var ignored = LogCapture.of(TurnRunner.class, records)) {
      result = loop(model, filler).run(state, SessionLimits.defaults());
    }

    assertEquals("second", assertInstanceOf(ResultMessage.Success.class, result).result());
    assertEquals("occupying", model.calls().getLast().getLast().content());
    var warning = records.getFirst();
    assertEquals(Level.WARNING, warning.getLevel());
    assertEquals(
        "{0} hook ''{1}'' Inject was dropped: steering queue full; session continues without"
            + " the injected message",
        warning.getMessage());
    assertEquals(List.of("PreStopHook", "filler"), List.of(warning.getParameters()));
  }
}
