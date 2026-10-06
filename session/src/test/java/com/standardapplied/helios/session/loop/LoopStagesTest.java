/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.MockModel;
import com.standardapplied.helios.session.CompactionResult;
import com.standardapplied.helios.session.ConcurrencyLimits;
import com.standardapplied.helios.session.ContextCompactor;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.CompactionPayload;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.HookRegistry;
import com.standardapplied.helios.session.hooks.OnUserMessageHook;
import com.standardapplied.helios.session.hooks.PostCompactHook;
import com.standardapplied.helios.session.hooks.PreCompactHook;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
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

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final List<QueryEvent> events = new CopyOnWriteArrayList<>();
  private final SteeringQueue queue = new SteeringQueue(1);
  private final SessionState state = new SessionState("sess-loop", new CancellationToken(), CLOCK);

  @AfterEach
  void shutDownScheduler() {
    scheduler.shutdownNow();
  }

  private AgentLoop loop(
      Model model, TokenCounter counter, ContextCompactor compactor, Hook... hooks) {
    var dispatch =
        new ToolDispatch(
            SessionContext.forTesting("sess-loop"),
            ToolRegistry.empty(),
            ConcurrencyLimits.defaults());
    var collaborators =
        new LoopCollaborators(
            new HookRegistry(List.of(hooks)),
            dispatch,
            queue,
            events::add,
            LoopStagesTest::context,
            CLOCK);
    var runner =
        new TurnRunner(
            collaborators,
            model,
            (modelId, usage) -> CostEstimate.ofMicroUsd(modelId.equals("summary") ? 7 : 1),
            null,
            scheduler);
    return new AgentLoop(collaborators, runner, new StopClassifier(), counter, compactor);
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
  void aPostCompactStopBeforeTheTurnEndsTheSessionWithoutCallingTheModel() {
    state.history().append(Message.system("system"));
    queue.offer(UserMessage.text("hello"));
    ContextCompactor keepLast = (history, s) -> CompactionResult.noOp(List.of(history.getLast()));
    PostCompactHook stop = (payload, ctx) -> HookOutcome.stop("compacted and stopped");
    var model = ScriptedModel.newBuilder().build();

    var result = loop(model, OVER_THE_WATERMARK, keepLast, stop).run(state, SMALL_WINDOW);

    assertEquals(
        "compacted and stopped", assertInstanceOf(ResultMessage.Success.class, result).result());
    assertEquals(List.of(), model.calls());
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
    assertSame(failure, warning.getThrown());
  }

  @Test
  void compactHookOutcomesTheirPhaseDoesNotHonourAreLoggedOnTheAgentLoopLogger() {
    state.history().append(Message.system("system"));
    queue.offer(UserMessage.text("hello"));
    var compacted = new AtomicBoolean();
    ContextCompactor keepLastOnce =
        (history, s) ->
            CompactionResult.noOp(
                compacted.compareAndSet(false, true) ? List.of(history.getLast()) : history);
    var blockingPre =
        new PreCompactHook() {
          @Override
          public String name() {
            return "pre";
          }

          @Override
          public HookOutcome beforeCompact(List<Message> history, HookContext ctx) {
            return HookOutcome.block("no");
          }
        };
    var injectingPost =
        new PostCompactHook() {
          @Override
          public String name() {
            return "post";
          }

          @Override
          public HookOutcome afterCompact(CompactionPayload payload, HookContext ctx) {
            return HookOutcome.inject("ignored");
          }
        };
    var model = ScriptedModel.newBuilder().withTextTurn("done").build();
    var records = new CopyOnWriteArrayList<LogRecord>();

    try (var ignored = LogCapture.of(AgentLoop.class, records)) {
      loop(model, OVER_THE_WATERMARK, keepLastOnce, blockingPre, injectingPost)
          .run(state, SMALL_WINDOW);
    }

    assertEquals(
        "PreCompactHook ''{0}'' {1} is not honored at this phase; using original history",
        records.get(0).getMessage());
    assertEquals(List.of("pre", "Block"), List.of(records.get(0).getParameters()));
    assertEquals(
        "PostCompactHook ''{0}'' {1} is not honored at this phase", records.get(1).getMessage());
    assertEquals(List.of("post", "Inject"), List.of(records.get(1).getParameters()));
    assertEquals(0, queue.size());
  }

  @Test
  void anOnUserMessageHookWithoutANameIsReportedUnderThePhaseName() {
    queue.offer(UserMessage.text("hello"));
    var unnamed =
        new OnUserMessageHook() {
          @Override
          public String name() {
            return null;
          }

          @Override
          public HookOutcome onUserMessage(UserMessage message, HookContext ctx) {
            return HookOutcome.block("not today");
          }
        };

    loop(new MockModel("unused"), unnamed).run(state, SessionLimits.defaults());

    var blocked =
        events.stream()
            .filter(QueryEvent.MessageBlocked.class::isInstance)
            .map(QueryEvent.MessageBlocked.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals("OnUserMessageHook", blocked.hookName());
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
          public HookOutcome beforeStop(
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
    try (var ignored = LogCapture.of(AgentLoop.class, records)) {
      result = loop(model, filler).run(state, SessionLimits.defaults());
    }

    assertEquals("second", assertInstanceOf(ResultMessage.Success.class, result).result());
    assertEquals("occupying", model.calls().getLast().getLast().content());
    var warning = records.getFirst();
    assertEquals(Level.WARNING, warning.getLevel());
    assertEquals(
        "PreStopHook ''{0}'' Inject was dropped: steering queue full; session will continue"
            + " without the injected message",
        warning.getMessage());
    assertEquals(List.of("filler"), List.of(warning.getParameters()));
  }
}
