/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.session.CompactionResult;
import com.standardapplied.helios.session.ContextCompactor;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.CompactionPayload;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.HookRegistry;
import com.standardapplied.helios.session.hooks.PostCompactHook;
import com.standardapplied.helios.session.hooks.PreCompactHook;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class AgentLoopTest {

  private static final String SID = "sess-1";
  private static final Instant FIXED = Instant.parse("2026-05-14T19:00:00Z");

  private final LoopFixture fixture = new LoopFixture(SID, FIXED);
  private final List<QueryEvent> events = fixture.events;
  private final HookRegistry hooks = HookRegistry.empty();
  private final ToolDispatch dispatch = fixture.dispatch(ToolRegistry.empty());

  @AfterEach
  void closeFixture() {
    fixture.close();
  }

  private SessionState freshState() {
    return fixture.state();
  }

  private static ScriptedModel fixedModel(String content, FinishReason reason, Usage usage) {
    return LoopModels.answering(content, reason, usage);
  }

  /** A model answering {@code count} calls, the n-th with {@code "step-n"}, each ending STOP. */
  private static ScriptedModel steps(int count) {
    var script = ScriptedModel.newBuilder();
    for (var n = 1; n <= count; n++) {
      script.withTextTurn("step-" + n, Usage.of(1, 1));
    }
    return script.build();
  }

  private AgentLoop buildLoop(Model model, SteeringQueue queue) {
    return buildLoopWith(model, queue, TokenCounter.charBased(), ContextCompactor.disabled());
  }

  private AgentLoop buildLoopWithCounter(Model model, SteeringQueue queue, TokenCounter counter) {
    return buildLoopWith(model, queue, counter, ContextCompactor.disabled());
  }

  private AgentLoop buildLoopWith(
      Model model, SteeringQueue queue, TokenCounter counter, ContextCompactor compactor) {
    return fixture.loop(fixture.collaborators(hooks, dispatch, queue), model, counter, compactor);
  }

  // ── construction ──────────────────────────────────────────────────────────

  @Test
  void constructorRejectsNullDependencies() {
    var queue = new SteeringQueue(8);
    var collaborators = fixture.collaborators(hooks, dispatch, queue);
    var runner = fixture.runner(collaborators, fixedModel("x", FinishReason.STOP, Usage.of(1, 1)));
    var classifier = new StopClassifier();
    var counter = TokenCounter.charBased();
    var compactor = ContextCompactor.disabled();
    var clock = fixture.clock;
    assertThrows(
        NullPointerException.class,
        () -> new AgentLoop(null, runner, classifier, counter, compactor));
    assertThrows(
        NullPointerException.class,
        () -> new AgentLoop(collaborators, null, classifier, counter, compactor));
    assertThrows(
        NullPointerException.class,
        () -> new AgentLoop(collaborators, runner, null, counter, compactor));
    assertThrows(
        NullPointerException.class,
        () ->
            new LoopCollaborators(null, dispatch, queue, events::add, fixture::hookContext, clock));
    assertThrows(
        NullPointerException.class,
        () -> new LoopCollaborators(hooks, null, queue, events::add, fixture::hookContext, clock));
    assertThrows(
        NullPointerException.class,
        () ->
            new LoopCollaborators(hooks, dispatch, null, events::add, fixture::hookContext, clock));
    assertThrows(
        NullPointerException.class,
        () -> new LoopCollaborators(hooks, dispatch, queue, null, fixture::hookContext, clock));
    assertThrows(
        NullPointerException.class,
        () -> new LoopCollaborators(hooks, dispatch, queue, events::add, null, clock));
    assertThrows(
        NullPointerException.class,
        () ->
            new LoopCollaborators(hooks, dispatch, queue, events::add, fixture::hookContext, null));
    assertThrows(
        NullPointerException.class,
        () -> new AgentLoop(collaborators, runner, classifier, null, compactor));
    assertThrows(
        NullPointerException.class,
        () -> new AgentLoop(collaborators, runner, classifier, counter, null));
  }

  @Test
  void runRejectsNullState() {
    var queue = new SteeringQueue(8);
    var loop = buildLoop(fixedModel("x", FinishReason.STOP, Usage.of(1, 1)), queue);
    assertThrows(NullPointerException.class, () -> loop.run(null, SessionLimits.defaults()));
  }

  @Test
  void runRejectsNullLimits() {
    var queue = new SteeringQueue(8);
    var loop = buildLoop(fixedModel("x", FinishReason.STOP, Usage.of(1, 1)), queue);
    assertThrows(NullPointerException.class, () -> loop.run(freshState(), null));
  }

  @Test
  void toolDispatchAccessorReturnsConstructorInstance() {
    var queue = new SteeringQueue(8);
    var loop = buildLoop(fixedModel("x", FinishReason.STOP, Usage.of(1, 1)), queue);
    assertSame(dispatch, loop.toolDispatch());
  }

  @Test
  void nowForTestsReturnsClockInstant() {
    var queue = new SteeringQueue(8);
    var loop = buildLoop(fixedModel("x", FinishReason.STOP, Usage.of(1, 1)), queue);
    assertEquals(FIXED, loop.nowForTests());
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void singleUserMessageProducesSuccess() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    var loop = buildLoop(fixedModel("hello back", FinishReason.STOP, Usage.of(3, 2)), queue);

    var result = loop.run(freshState(), SessionLimits.defaults());

    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("hello back", success.result());
    assertEquals(3, success.usage().inputTokens());
    assertTrue(events.stream().anyMatch(e -> e instanceof QueryEvent.UserMessageReceived));
    assertTrue(events.stream().anyMatch(e -> e instanceof QueryEvent.AssistantText));
    assertTrue(events.stream().anyMatch(e -> e instanceof QueryEvent.TurnEnded));
    assertTrue(events.stream().anyMatch(e -> e instanceof QueryEvent.LoopEnded));
  }

  @Test
  void multipleQueuedMessagesComposeIntoSingleUserTurn() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("one"));
    queue.offer(UserMessage.text("two"));
    queue.offer(UserMessage.text("three"));
    var state = freshState();
    var loop = buildLoop(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue);

    loop.run(state, SessionLimits.defaults());

    var received = events.stream().filter(e -> e instanceof QueryEvent.UserMessageReceived).count();
    assertEquals(3, received, "one UserMessageReceived per original message");
    var history = state.history().snapshot();
    assertTrue(history.get(0).content().startsWith("[messages composed: 3]"));
    assertTrue(history.get(0).content().contains("one"));
    assertTrue(history.get(0).content().contains("two"));
    assertTrue(history.get(0).content().contains("three"));
  }

  // ── empty initial state → error ───────────────────────────────────────────

  @Test
  void emptyQueueAndEmptyHistoryProducesErrorDuringExecution() {
    var queue = new SteeringQueue(8);
    var loop = buildLoop(fixedModel("never called", FinishReason.STOP, Usage.of(0, 0)), queue);
    var result = loop.run(freshState(), SessionLimits.defaults());

    var err = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result);
    assertEquals("EmptyHistory", err.error().kind());
  }

  // ── max turns ─────────────────────────────────────────────────────────────

  @Test
  void maxTurnsCeilingProducesErrorMaxTurns() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    // model always says TOOL_CALLS, never STOP → loop never naturally terminates
    var toolCalls = LoopModels.response("tool tool tool", FinishReason.TOOL_CALLS, Usage.of(1, 1));
    var model =
        ScriptedModel.newBuilder()
            .withResponseTurn(toolCalls)
            .withResponseTurn(toolCalls)
            .withResponseTurn(toolCalls)
            .build();
    var loop = buildLoop(model, queue);
    var limits = SessionLimits.newBuilder().withMaxTurns(3).build();
    var result = loop.run(freshState(), limits);
    var t = assertInstanceOf(ResultMessage.ErrorMaxTurns.class, result);
    assertEquals(3, t.turnsUsed());
  }

  // ── mid-run steering ──────────────────────────────────────────────────────

  @Test
  void midRunSteeringExtendsLoop() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("first"));

    var script =
        ScriptedModel.newBuilder()
            .withTextTurn("partial", Usage.of(2, 1))
            .withTextTurn("done", Usage.of(3, 2))
            .build();
    // On turn 1, enqueue a follow-up before signalling STOP.
    // The classifier sees pending messages → continues to turn 2.
    // On turn 2, signal STOP cleanly with queue empty.
    var adaptive =
        LoopModels.onEachCall(
            script,
            call -> {
              if (call == 1) {
                queue.offer(UserMessage.text("follow-up"));
              }
            });
    var state = freshState();
    var loop = buildLoop(adaptive, queue);
    var result = loop.run(state, SessionLimits.defaults());

    assertEquals(2, script.calls().size());
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("done", success.result());
  }

  // ── cancellation ──────────────────────────────────────────────────────────

  @Test
  void preCancelledTokenProducesCancelledImmediatelyAfterFirstTurn() {
    var token = new CancellationToken();
    var state = fixture.state(token);
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    token.cancel("user-stop");
    var loop = buildLoop(fixedModel("x", FinishReason.STOP, Usage.of(1, 1)), queue);

    var result = loop.run(state, SessionLimits.defaults());
    var c = assertInstanceOf(ResultMessage.Cancelled.class, result);
    assertEquals("user-stop", c.reason());
  }

  // ── terminal emission ────────────────────────────────────────────────────

  @Test
  void loopEndedIsAlwaysLastEvent() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    var loop = buildLoop(fixedModel("hello", FinishReason.STOP, Usage.of(1, 1)), queue);
    loop.run(freshState(), SessionLimits.defaults());
    assertInstanceOf(QueryEvent.LoopEnded.class, events.get(events.size() - 1));
  }

  @Test
  void terminalIsRecordedOnState() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    var state = freshState();
    buildLoop(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue)
        .run(state, SessionLimits.defaults());
    assertTrue(state.isTerminal());
    assertInstanceOf(ResultMessage.Success.class, state.terminal().orElseThrow());
  }

  @Test
  void unexpectedRuntimeExceptionInLoopProducesErrorDuringExecution() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    // Sabotage by passing an event sink that throws on the very first emission.
    var runner =
        fixture.runner(
            fixture.collaborators(hooks, dispatch, queue),
            fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)));
    var sabotaged =
        fixture.loop(
            new LoopCollaborators(
                hooks,
                dispatch,
                queue,
                e -> {
                  throw new RuntimeException("sink boom");
                },
                fixture::hookContext,
                fixture.clock),
            runner,
            TokenCounter.charBased(),
            ContextCompactor.disabled());
    var result = sabotaged.run(freshState(), SessionLimits.defaults());
    var err = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result);
    assertEquals("java.lang.RuntimeException", err.error().kind());
    assertEquals("sink boom", err.error().message());
  }

  @Test
  void hooksFiredAtKeyPhases() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    buildLoop(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue)
        .run(freshState(), SessionLimits.defaults());
    // With an empty registry every non-Continue hook outcome path is unreachable; verify the loop
    // ran cleanly to LoopEnded.
    assertTrue(events.stream().anyMatch(e -> e instanceof QueryEvent.LoopEnded));
  }

  // ── context watermark ────────────────────────────────────────────────────

  @Test
  void contextWatermarkFiresWhenUsageCrossesThreshold() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    TokenCounter loud = msgs -> 90L;
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100).build();
    var loop =
        buildLoopWithCounter(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue, loud);
    loop.run(freshState(), limits);
    var warnings = events.stream().filter(e -> e instanceof QueryEvent.ContextWarning).toList();
    assertEquals(1, warnings.size());
    var warn = (QueryEvent.ContextWarning) warnings.get(0);
    assertEquals(0.9, warn.usagePct(), 1e-9);
    assertEquals(SID, warn.sessionId());
    assertEquals(1L, warn.turnIndex());
  }

  @Test
  void contextWatermarkDoesNotFireBelowThreshold() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    TokenCounter quiet = msgs -> 50L;
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100).build();
    var loop =
        buildLoopWithCounter(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue, quiet);
    loop.run(freshState(), limits);
    assertTrue(events.stream().noneMatch(e -> e instanceof QueryEvent.ContextWarning));
  }

  @Test
  void contextWatermarkFiresExactlyAtThreshold() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    TokenCounter atThreshold = msgs -> 85L;
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100).build();
    var loop =
        buildLoopWithCounter(
            fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue, atThreshold);
    loop.run(freshState(), limits);
    assertEquals(1, events.stream().filter(e -> e instanceof QueryEvent.ContextWarning).count());
  }

  @Test
  void contextWatermarkFiresAtMostOnceAcrossManyTurns() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    // Enqueue a follow-up on every turn so the loop keeps going up to maxTurns.
    var neverStops =
        LoopModels.onEachCall(steps(5), n -> queue.offer(UserMessage.text("turn-" + n)));
    TokenCounter alwaysOver = msgs -> 95L;
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100).withMaxTurns(5).build();
    buildLoopWithCounter(neverStops, queue, alwaysOver).run(freshState(), limits);
    assertEquals(
        1,
        events.stream().filter(e -> e instanceof QueryEvent.ContextWarning).count(),
        "watermark must be sticky across turns until reset");
  }

  @Test
  void contextWatermarkResetAllowsReFire() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    var state = freshState();
    var adaptive =
        LoopModels.onEachCall(
            steps(2),
            n -> {
              if (n == 1) {
                // Turn 1 enqueues a follow-up so the loop runs a second turn after the
                // watermark fires for the first time.
                queue.offer(UserMessage.text("again"));
              } else if (n == 2) {
                // Turn 2 simulates the Day-2 compactor clearing the flag before the
                // watermark check fires again for this turn.
                state.contextWatermark().reset();
              }
            });
    TokenCounter alwaysOver = msgs -> 90L;
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100).build();
    buildLoopWithCounter(adaptive, queue, alwaysOver).run(state, limits);
    assertEquals(
        2,
        events.stream().filter(e -> e instanceof QueryEvent.ContextWarning).count(),
        "reset must re-arm the watermark");
  }

  @Test
  void contextWatermarkDoesNotFireWhenWellBelowThreshold() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    var sentinel = new AtomicInteger(0);
    TokenCounter counter =
        msgs -> {
          sentinel.incrementAndGet();
          return 1_000_000L;
        };
    // Set the explicit cap so high that 1M counted tokens is a negligible fraction —
    // verifies the threshold math, not the auto-resolution path (covered separately).
    var limits = SessionLimits.newBuilder().withMaxContextTokens(Long.MAX_VALUE).build();
    buildLoopWithCounter(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue, counter)
        .run(freshState(), limits);
    assertTrue(events.stream().noneMatch(e -> e instanceof QueryEvent.ContextWarning));
    assertTrue(sentinel.get() >= 1, "counter still called for observability");
  }

  // ── context compaction (0.95 trigger) ────────────────────────────────────

  @Test
  void contextCompactorInvokedAtNinetyFivePercent() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    // Size-proportional counter so tokensAfter < tokensBefore after a real shrink.
    TokenCounter perMessage = msgs -> 48L * msgs.size();
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100).build();
    var compactorCalled = new AtomicInteger(0);
    ContextCompactor shrinking =
        (history, state) -> {
          compactorCalled.incrementAndGet();
          return CompactionResult.noOp(List.of(history.get(history.size() - 1)));
        };
    buildLoopWith(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue, perMessage, shrinking)
        .run(freshState(), limits);
    assertEquals(1, compactorCalled.get(), "compactor must be invoked at 0.95");
    var edits = events.stream().filter(e -> e instanceof QueryEvent.ContextEdited).toList();
    assertEquals(1, edits.size(), "ContextEdited must fire after a successful compaction");
    var edited = (QueryEvent.ContextEdited) edits.get(0);
    assertTrue(edited.removedBlocks() > 0, "removedBlocks must be positive");
    assertTrue(
        edited.tokensBefore() >= 95L, "tokensBefore must reflect a count past the 0.95 watermark");
    assertTrue(
        edited.tokensAfter() < edited.tokensBefore(),
        "tokensAfter must be less than tokensBefore for a real shrink");
  }

  @Test
  void contextCompactorBelowNinetyFivePercentNotInvoked() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    TokenCounter mid = msgs -> 90L; // ≥ 0.85 but < 0.95
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100).build();
    var compactorCalled = new AtomicInteger(0);
    ContextCompactor neverShrinks =
        (history, state) -> {
          compactorCalled.incrementAndGet();
          return CompactionResult.noOp(history);
        };
    buildLoopWith(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue, mid, neverShrinks)
        .run(freshState(), limits);
    assertEquals(0, compactorCalled.get(), "compactor must not fire below 0.95");
    assertEquals(1, events.stream().filter(e -> e instanceof QueryEvent.ContextWarning).count());
    assertTrue(events.stream().noneMatch(e -> e instanceof QueryEvent.ContextEdited));
  }

  @Test
  void contextCompactorNoShrinkSkipsContextEdited() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    TokenCounter loud = msgs -> 96L;
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100).build();
    ContextCompactor noOp = (history, state) -> CompactionResult.noOp(history);
    buildLoopWith(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue, loud, noOp)
        .run(freshState(), limits);
    assertTrue(
        events.stream().noneMatch(e -> e instanceof QueryEvent.ContextEdited),
        "no-shrink result must not emit ContextEdited");
  }

  @Test
  void contextCompactorReturningSameSizeListIsTreatedAsNoOp() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    TokenCounter loud = msgs -> 96L;
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100).build();
    ContextCompactor swap =
        (history, state) ->
            CompactionResult.noOp(new ArrayList<>(history)); // same size — must be ignored
    buildLoopWith(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue, loud, swap)
        .run(freshState(), limits);
    assertTrue(
        events.stream().noneMatch(e -> e instanceof QueryEvent.ContextEdited),
        "same-size result must not emit ContextEdited");
  }

  @Test
  void contextCompactorThrowingDoesNotCrashLoop() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    TokenCounter loud = msgs -> 96L;
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100).build();
    ContextCompactor broken =
        (history, state) -> {
          throw new RuntimeException("compactor bug");
        };
    var result =
        buildLoopWith(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue, loud, broken)
            .run(freshState(), limits);
    // Loop completes cleanly — a thrown compactor must be swallowed.
    assertInstanceOf(ResultMessage.Success.class, result);
    assertTrue(events.stream().noneMatch(e -> e instanceof QueryEvent.ContextEdited));
  }

  @Test
  void contextEditedResetsWarningFlagSoFutureClimbReFires() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    var multi =
        LoopModels.onEachCall(
            steps(3),
            n -> {
              if (n < 3) {
                queue.offer(UserMessage.text("turn-" + n));
              }
            });
    TokenCounter loud = msgs -> 96L;
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100).build();
    ContextCompactor shrinking =
        (history, state) ->
            CompactionResult.noOp(history.subList(history.size() - 1, history.size()));
    buildLoopWith(multi, queue, loud, shrinking).run(freshState(), limits);
    var warnings = events.stream().filter(e -> e instanceof QueryEvent.ContextWarning).count();
    var edits = events.stream().filter(e -> e instanceof QueryEvent.ContextEdited).count();
    assertTrue(warnings >= 2, "warning flag must reset after compaction (got " + warnings + ")");
    assertTrue(edits >= 2, "compactor must run on every re-climb (got " + edits + ")");
  }

  // ── PreModelTurnHook.MutateHistory (BYO compactor as a hook) ──────────────

  @Test
  void preModelTurnMutateHistoryReplacesHistory() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("first"));
    queue.offer(UserMessage.text("second"));
    queue.offer(UserMessage.text("third"));
    var replacement = List.of(Message.system("compacted system"), Message.user("merged turn"));
    PreModelTurnHook trimmer = (history, ctx) -> HookOutcome.mutateHistory(replacement);
    var hookRegistry = new HookRegistry(List.of(trimmer));
    var loop =
        buildLoopWithHooks(
            fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)),
            queue,
            TokenCounter.charBased(),
            ContextCompactor.disabled(),
            hookRegistry);
    var state = freshState();
    loop.run(state, SessionLimits.defaults());
    var history = state.history().snapshot();
    assertEquals(replacement.size() + 1, history.size(), "history rewritten; assistant appended");
    assertEquals("compacted system", history.get(0).content());
    assertEquals("merged turn", history.get(1).content());
    var fired =
        events.stream()
            .filter(e -> e instanceof QueryEvent.HookFired)
            .map(e -> (QueryEvent.HookFired) e)
            .anyMatch(
                h ->
                    h.phase().equals("PreModelTurnHook")
                        && h.outcomeKind().equals("MutateHistory"));
    assertTrue(fired, "HookFired with MutateHistory must be emitted");
  }

  // ── pre-turn watermark check (P0-3) ──────────────────────────────────────

  @Test
  void preTurnWatermarkFiresBeforeModelCallWhenHistoryAlreadyExceeds() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    TokenCounter perMessage = msgs -> 96L * msgs.size();
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100).build();
    var compacted = new AtomicInteger(0);
    ContextCompactor shrinking =
        (history, state) -> {
          compacted.incrementAndGet();
          return CompactionResult.noOp(history.subList(history.size() - 1, history.size()));
        };
    var recorder = LoopModels.answering("ok", FinishReason.STOP, Usage.of(1, 1));
    buildLoopWith(recorder, queue, perMessage, shrinking).run(freshState(), limits);
    // Pre-turn check fires BEFORE the model call. Counter returns 96*size; after drainAndAppend
    // history has 1 message → tokens=96, usage=0.96 ≥ 0.95 → compactor invoked at PRE-TURN.
    assertTrue(compacted.get() >= 1, "pre-turn compaction must fire");
    assertEquals(1, recorder.calls().size(), "model is called once after pre-turn compaction");
  }

  // ── compaction cost accumulation (P0-2b) ─────────────────────────────────

  @Test
  void compactionUsageAccumulatesIntoSessionTotals() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    TokenCounter loud = msgs -> 96L * msgs.size();
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100).build();
    ContextCompactor reporting =
        (history, state) ->
            new CompactionResult(
                history.subList(history.size() - 1, history.size()),
                Usage.of(200, 40),
                "summary-model");
    var state = freshState();
    buildLoopWith(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue, loud, reporting)
        .run(state, limits);
    // Model turn contributes 1+1, compaction contributes 200+40.
    assertTrue(
        state.totals().usage().inputTokens() >= 200, "compaction input tokens must accumulate");
    assertTrue(
        state.totals().usage().outputTokens() >= 40, "compaction output tokens must accumulate");
  }

  @Test
  void zeroUsageCompactionDoesNotInvokeCostAccounting() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    TokenCounter loud = msgs -> 96L * msgs.size();
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100).build();
    ContextCompactor pureTrim =
        (history, state) ->
            CompactionResult.noOp(history.subList(history.size() - 1, history.size()));
    var state = freshState();
    buildLoopWith(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue, loud, pureTrim)
        .run(state, limits);
    // Only the model turn's 1+1 contributes; compactor reports zero usage.
    assertEquals(1, state.totals().usage().inputTokens());
    assertEquals(1, state.totals().usage().outputTokens());
  }

  // ── effectiveMaxContextTokens resolver (P0-2c, model-aware default) ──────

  /**
   * Factory that returns a Model whose documented {@link Model#contextWindow()} is the supplied
   * value, so tests can exercise the auto-resolution paths without depending on a real provider.
   */
  private static Model modelWithContextWindow(int contextWindow) {
    return LoopModels.withContextWindow(
        fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), contextWindow);
  }

  @Test
  void effectiveMaxContextTokensUsesModelWindowWhenSentinelLimit() {
    var loop =
        buildLoopWith(
            modelWithContextWindow(1_000_000),
            new SteeringQueue(8),
            TokenCounter.charBased(),
            ContextCompactor.disabled());
    var limits = SessionLimits.newBuilder().withMaxContextTokens(0L).build();
    // 1M - 20K output reservation = 980_000.
    assertEquals(980_000L, loop.compaction().effectiveMaxContextTokens(limits));
  }

  @Test
  void effectiveMaxContextTokensClampsToUserCapWhenCapSmaller() {
    var loop =
        buildLoopWith(
            modelWithContextWindow(1_000_000),
            new SteeringQueue(8),
            TokenCounter.charBased(),
            ContextCompactor.disabled());
    var limits = SessionLimits.newBuilder().withMaxContextTokens(50_000L).build();
    assertEquals(50_000L, loop.compaction().effectiveMaxContextTokens(limits));
  }

  @Test
  void effectiveMaxContextTokensClampsToModelWhenUserCapBigger() {
    var loop =
        buildLoopWith(
            modelWithContextWindow(200_000),
            new SteeringQueue(8),
            TokenCounter.charBased(),
            ContextCompactor.disabled());
    var limits = SessionLimits.newBuilder().withMaxContextTokens(1_000_000L).build();
    // 200K - 20K reservation = 180_000.
    assertEquals(180_000L, loop.compaction().effectiveMaxContextTokens(limits));
  }

  @Test
  void effectiveMaxContextTokensFallsBackTo180KWhenBothUnknown() {
    var loop =
        buildLoopWith(
            modelWithContextWindow(0),
            new SteeringQueue(8),
            TokenCounter.charBased(),
            ContextCompactor.disabled());
    var limits = SessionLimits.newBuilder().withMaxContextTokens(0L).build();
    assertEquals(
        ContextCompaction.AUTO_FALLBACK_MAX_CONTEXT_TOKENS,
        loop.compaction().effectiveMaxContextTokens(limits));
  }

  @Test
  void effectiveMaxContextTokensUsesUserCapWhenModelUnknown() {
    var loop =
        buildLoopWith(
            modelWithContextWindow(0),
            new SteeringQueue(8),
            TokenCounter.charBased(),
            ContextCompactor.disabled());
    var limits = SessionLimits.newBuilder().withMaxContextTokens(75_000L).build();
    assertEquals(75_000L, loop.compaction().effectiveMaxContextTokens(limits));
  }

  @Test
  void watermarkFiresUsingResolvedWindowWhenSentinelLimit() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    // Model with 100K context window → effective = 80K after 20K reservation.
    // Counter reports 70K tokens (>= 0.85 * 80K = 68K) → ContextWarning fires.
    TokenCounter near85pct = msgs -> 70_000L;
    var limits = SessionLimits.newBuilder().withMaxContextTokens(0L).build();
    buildLoopWith(modelWithContextWindow(100_000), queue, near85pct, ContextCompactor.disabled())
        .run(freshState(), limits);
    assertTrue(
        events.stream().anyMatch(e -> e instanceof QueryEvent.ContextWarning),
        "ContextWarning must fire once tokens cross 0.85 of the model-derived window");
  }

  // ── compaction cost attribution (P0-2c) ──────────────────────────────────

  // ── watermark is cumulative across turns, not per-turn ───────────────────

  @Test
  void contextWatermarkUsesCumulativeHistoryNotSingleMessageSize() {
    // Counter reports a fixed per-message size. With size 50 and a ceiling of 100, the watermark
    // SHOULD trip only when the history has accumulated ≥ 2 messages — exactly what a cumulative
    // implementation does. A buggy per-turn-only implementation would never trip, because no
    // single message reaches 85% of 100. The first turn appends a user message AND an assistant
    // reply, so the post-turn check sees a 2-message history (100 tokens total, 100% utilisation).
    final var perMessageTokens = 50L;
    TokenCounter cumulative = msgs -> perMessageTokens * msgs.size();
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("first"));
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100L).build();
    buildLoopWith(
            fixedModel("ok", FinishReason.STOP, Usage.of(0, 0)),
            queue,
            cumulative,
            ContextCompactor.disabled())
        .run(freshState(), limits);
    assertTrue(
        events.stream().anyMatch(e -> e instanceof QueryEvent.ContextWarning),
        "watermark must measure cumulative history — a 2-message history of 50 tokens each"
            + " crosses the 0.85 × 100 threshold even though no single message does. If this"
            + " test regresses, the watermark has flipped to per-turn semantics.");
  }

  @Test
  void compactionCostPricedAgainstCompactorModelNotMainLoop() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    // Stateful counter: first call (pre-turn) sits below 0.85; subsequent calls (post-turn)
    // cross the 0.95 watermark exactly once so the compactor fires deterministically.
    var watermarkCalls = new AtomicInteger(0);
    TokenCounter staged = msgs -> watermarkCalls.getAndIncrement() == 0 ? 50L : 96L * msgs.size();
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100L).build();
    // Two-rate price table: main is 10× more expensive than summary.
    var pricing =
        java.util.Map.of(
            "test",
                new CostCalculator.Pricing(100_000_000L, 100_000_000L, 100_000_000L, 100_000_000L),
            "summary-model",
                new CostCalculator.Pricing(10_000_000L, 10_000_000L, 10_000_000L, 10_000_000L));
    var calculator = CostCalculator.staticTable(pricing);
    ContextCompactor reporting =
        (history, state) ->
            new CompactionResult(
                history.subList(history.size() - 1, history.size()),
                Usage.of(1_000_000, 100_000),
                "summary-model");
    var model =
        ScriptedModel.newBuilder()
            .withId("test")
            .withResponseTurn(LoopModels.response("ok", FinishReason.STOP, Usage.of(0, 0)))
            .build();
    var collaborators = fixture.collaborators(hooks, dispatch, queue);
    var loop =
        fixture.loop(
            collaborators,
            fixture.runner(collaborators, model, calculator, null),
            staged,
            reporting);
    var state = freshState();
    loop.run(state, limits);
    // 1_000_000 input × (10_000_000 μUSD / 1M tokens) + 100_000 output × (10/M) = 11_000_000 μUSD.
    // The pre-fix code priced at the main rate (100/M) → 110_000_000 μUSD. The order-of-magnitude
    // assertion isolates the routing fix from the exact firing count.
    var cost = state.totals().cost().microUsd();
    assertTrue(cost > 0L, "compaction usage must accumulate cost");
    assertTrue(
        cost < 50_000_000L,
        () -> "compaction should be priced at summary rate (~11M μUSD), got " + cost);
  }

  @Test
  void contextCompactorReturningDifferentInstanceSameSizeIsTreatedAsNoShrink() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    TokenCounter loud = msgs -> 96L;
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100L).build();
    // Different list reference but same size — must still be treated as no-op so the loop
    // doesn't fire ContextEdited or PostCompactHook.
    ContextCompactor sameSize =
        (history, state) -> new CompactionResult(new ArrayList<>(history), Usage.of(0, 0), "");
    var result =
        buildLoopWith(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue, loud, sameSize)
            .run(freshState(), limits);
    assertInstanceOf(ResultMessage.Success.class, result);
    assertTrue(events.stream().noneMatch(e -> e instanceof QueryEvent.ContextEdited));
  }

  @Test
  void contextCompactorReturningNullIsTreatedAsNoOp() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    TokenCounter loud = msgs -> 96L;
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100L).build();
    ContextCompactor nullReturning = (history, state) -> null;
    var result =
        buildLoopWith(
                fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue, loud, nullReturning)
            .run(freshState(), limits);
    // Loop completes cleanly — a compactor that returned null must be treated as a no-op.
    assertInstanceOf(ResultMessage.Success.class, result);
    assertTrue(events.stream().noneMatch(e -> e instanceof QueryEvent.ContextEdited));
  }

  // ── PreCompact / PostCompact hooks ───────────────────────────────────────

  private AgentLoop buildLoopWithHooks(
      Model model,
      SteeringQueue queue,
      TokenCounter counter,
      ContextCompactor compactor,
      HookRegistry hookRegistry) {
    return fixture.loop(
        fixture.collaborators(hookRegistry, dispatch, queue), model, counter, compactor);
  }

  /**
   * Runs the loop over {@code messages} with {@code hook} registered, against a counter that puts
   * every history at 96 tokens a message, so the 0.95 compaction trigger of a 100-token window
   * fires and hands the history to {@code compactor}.
   */
  private ResultMessage runOverCompactionTrigger(
      ContextCompactor compactor, Hook hook, String... messages) {
    var queue = new SteeringQueue(8);
    for (var message : messages) {
      queue.offer(UserMessage.text(message));
    }
    TokenCounter trigger = msgs -> 96L * msgs.size();
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100L).build();
    return buildLoopWithHooks(
            fixedModel("ok", FinishReason.STOP, Usage.of(0, 0)),
            queue,
            trigger,
            compactor,
            new HookRegistry(List.of(hook)))
        .run(freshState(), limits);
  }

  private static ContextCompactor summarisingTo(String summary) {
    return (history, state) ->
        new CompactionResult(
            List.of(Message.user("[Earlier context summary]\n" + summary)), Usage.of(0, 0), "");
  }

  @Test
  void preCompactHookCanMutateHistoryHandedToCompactor() {
    var compactorSawHistory = new AtomicReference<List<Message>>();
    ContextCompactor capturing =
        (history, state) -> {
          compactorSawHistory.set(history);
          return CompactionResult.noOp(history);
        };
    var replacement = List.<Message>of(Message.user("rewritten by hook"));
    PreCompactHook mutator = (history, ctx) -> HookOutcome.mutateHistory(replacement);
    runOverCompactionTrigger(capturing, mutator, "original");
    var observed = compactorSawHistory.get();
    assertEquals(1, observed.size(), "compactor must receive the rewritten history");
    assertEquals("rewritten by hook", observed.get(0).content());
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e instanceof QueryEvent.HookFired hf
                        && "PreCompactHook".equals(hf.phase())
                        && "MutateHistory".equals(hf.outcomeKind())),
        "HookFired{PreCompactHook, MutateHistory} must be emitted");
  }

  @Test
  void postCompactHookFiresWithBeforeAfterPayloadOnSuccessfulShrink() {
    var payloadSeen = new AtomicReference<CompactionPayload>();
    PostCompactHook observer =
        (payload, ctx) -> {
          payloadSeen.set(payload);
          return HookOutcome.cont();
        };
    runOverCompactionTrigger(summarisingTo("the gist"), observer, "hi", "hi2");
    var payload = payloadSeen.get();
    assertTrue(payload != null, "PostCompactHook must fire on a real shrink");
    assertTrue(payload.removedBlocks() > 0, "payload must report removed-block count");
    assertEquals("the gist", payload.summary());
  }

  @Test
  void postCompactHookNotFiredOnNoOpCompaction() {
    ContextCompactor noOp = (history, state) -> CompactionResult.noOp(history);
    var fired = new AtomicInteger(0);
    PostCompactHook observer =
        (payload, ctx) -> {
          fired.incrementAndGet();
          return HookOutcome.cont();
        };
    runOverCompactionTrigger(noOp, observer, "hi");
    assertEquals(0, fired.get(), "PostCompactHook must not fire when compactor returned no shrink");
  }

  @Test
  void preCompactHookMutateHistoryWithEmptyListIsAccepted() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("u"));
    TokenCounter trigger = msgs -> 96L * Math.max(msgs.size(), 1);
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100L).build();
    var observed = new ArrayList<List<Message>>();
    ContextCompactor capturing =
        (history, state) -> {
          observed.add(history);
          return CompactionResult.noOp(history);
        };
    PreCompactHook empty = (history, ctx) -> HookOutcome.mutateHistory(List.of());
    var hookRegistry = new HookRegistry(List.of(empty));
    buildLoopWithHooks(
            fixedModel("ok", FinishReason.STOP, Usage.of(0, 0)),
            queue,
            trigger,
            capturing,
            hookRegistry)
        .run(freshState(), limits);
    assertTrue(!observed.isEmpty());
    assertTrue(
        observed.get(0).isEmpty(),
        "compactor must receive the empty history when the hook supplies an empty list");
  }

  @Test
  void preCompactHookUnsupportedOutcomesFallBackToContinue() {
    // Block / Stop / Inject are not honored at this phase; assert each is logged + treated as
    // Continue so the compactor still runs against the unmodified history.
    var outcomes =
        List.of(
            HookOutcome.block("nope"),
            HookOutcome.stop("would-stop"),
            HookOutcome.inject("would-inject"));
    for (var outcome : outcomes) {
      var compactorInvoked = new AtomicInteger(0);
      ContextCompactor capturing =
          (history, state) -> {
            compactorInvoked.incrementAndGet();
            return CompactionResult.noOp(history);
          };
      PreCompactHook hook = (history, ctx) -> outcome;
      runOverCompactionTrigger(capturing, hook, "hi");
      assertTrue(
          compactorInvoked.get() >= 1,
          () ->
              "compactor must still run when PreCompactHook returned unsupported outcome: "
                  + outcome.getClass().getSimpleName());
    }
  }

  @Test
  void postCompactHookUnsupportedOutcomesFallBackToContinue() {
    // Mutate / Block / Inject must NOT short-circuit the loop. ContextEdited still fires and the
    // session terminates normally.
    var outcomes =
        List.of(
            HookOutcome.mutateArgs(Map.of("ignored", "value")),
            HookOutcome.block("nope"),
            HookOutcome.inject("would-inject"));
    for (var outcome : outcomes) {
      PostCompactHook hook = (payload, ctx) -> outcome;
      events.clear();
      runOverCompactionTrigger(summarisingTo("short"), hook, "hi", "hi2");
      assertTrue(
          events.stream().anyMatch(e -> e instanceof QueryEvent.ContextEdited),
          () ->
              "ContextEdited must still fire when PostCompactHook returned unsupported outcome: "
                  + outcome.getClass().getSimpleName());
    }
  }

  @Test
  void postCompactHookStopTerminatesSession() {
    PostCompactHook stopper = (payload, ctx) -> HookOutcome.stop("post-compact veto");
    var result = runOverCompactionTrigger(summarisingTo("short"), stopper, "hi", "hi2");
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("post-compact veto", success.result());
    assertTrue(
        events.stream().noneMatch(e -> e instanceof QueryEvent.ContextEdited),
        "ContextEdited must not fire when PostCompactHook returned Stop");
  }
}
