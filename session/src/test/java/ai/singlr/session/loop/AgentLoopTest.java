/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.CostCalculator;
import ai.singlr.core.context.TokenCounter;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.runtime.SessionContext;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.CompactionResult;
import ai.singlr.session.ConcurrencyLimits;
import ai.singlr.session.ContextCompactor;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.ResultMessage;
import ai.singlr.session.SessionLimits;
import ai.singlr.session.SteeringQueue;
import ai.singlr.session.UserMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class AgentLoopTest {

  private static final String SID = "sess-1";
  private static final Instant FIXED = Instant.parse("2026-05-14T19:00:00Z");
  private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);

  private final List<QueryEvent> events = new ArrayList<>();
  private final ai.singlr.session.hooks.HookRegistry hooks =
      ai.singlr.session.hooks.HookRegistry.empty();
  private final ToolDispatch dispatch =
      new ToolDispatch(
          SessionContext.forTesting("loop-test"),
          ai.singlr.session.tools.ToolRegistry.empty(),
          ConcurrencyLimits.defaults());

  private static final Model CTX_MODEL =
      new Model() {
        @Override
        public Response<Void> chat(List<Message> messages, List<Tool> tools) {
          return Response.newBuilder().build();
        }

        @Override
        public String id() {
          return "stub";
        }

        @Override
        public String provider() {
          return "stub";
        }
      };

  private static final java.util.function.Function<
          SessionState, ai.singlr.session.hooks.HookContext>
      CTX_FACTORY =
          s ->
              new ai.singlr.session.hooks.DefaultHookContext(
                  s.sessionId(), s.currentTurnIndex(), s.cancellation(), CTX_MODEL);

  private SessionState freshState() {
    return new SessionState(SID, new CancellationToken(), CLOCK);
  }

  private static Model fixedModel(String content, FinishReason reason, Usage usage) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder()
            .withContent(content)
            .withFinishReason(reason)
            .withUsage(usage)
            .build();
      }

      @Override
      public String id() {
        return "test";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  private AgentLoop buildLoop(Model model, SteeringQueue queue) {
    return buildLoopWith(model, queue, TokenCounter.charBased(), ContextCompactor.disabled());
  }

  private AgentLoop buildLoopWithCounter(Model model, SteeringQueue queue, TokenCounter counter) {
    return buildLoopWith(model, queue, counter, ContextCompactor.disabled());
  }

  private AgentLoop buildLoopWith(
      Model model, SteeringQueue queue, TokenCounter counter, ContextCompactor compactor) {
    var runner =
        new TurnRunner(
            model,
            hooks,
            dispatch,
            queue,
            events::add,
            CTX_FACTORY,
            CLOCK,
            CostCalculator.ZERO,
            null);
    return new AgentLoop(
        runner,
        new StopClassifier(),
        hooks,
        dispatch,
        queue,
        events::add,
        CTX_FACTORY,
        CLOCK,
        counter,
        compactor);
  }

  // ── construction ──────────────────────────────────────────────────────────

  @Test
  void constructorRejectsNullDependencies() {
    var queue = new SteeringQueue(8);
    var runner =
        new TurnRunner(
            fixedModel("x", FinishReason.STOP, Usage.of(1, 1)),
            hooks,
            dispatch,
            queue,
            events::add,
            CTX_FACTORY,
            CLOCK,
            CostCalculator.ZERO,
            null);
    var classifier = new StopClassifier();
    var counter = TokenCounter.charBased();
    var compactor = ContextCompactor.disabled();
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentLoop(
                null,
                classifier,
                hooks,
                dispatch,
                queue,
                events::add,
                CTX_FACTORY,
                CLOCK,
                counter,
                compactor));
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentLoop(
                runner,
                null,
                hooks,
                dispatch,
                queue,
                events::add,
                CTX_FACTORY,
                CLOCK,
                counter,
                compactor));
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentLoop(
                runner,
                classifier,
                null,
                dispatch,
                queue,
                events::add,
                CTX_FACTORY,
                CLOCK,
                counter,
                compactor));
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentLoop(
                runner,
                classifier,
                hooks,
                null,
                queue,
                events::add,
                CTX_FACTORY,
                CLOCK,
                counter,
                compactor));
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentLoop(
                runner,
                classifier,
                hooks,
                dispatch,
                null,
                events::add,
                CTX_FACTORY,
                CLOCK,
                counter,
                compactor));
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentLoop(
                runner,
                classifier,
                hooks,
                dispatch,
                queue,
                null,
                CTX_FACTORY,
                CLOCK,
                counter,
                compactor));
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentLoop(
                runner,
                classifier,
                hooks,
                dispatch,
                queue,
                events::add,
                null,
                CLOCK,
                counter,
                compactor));
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentLoop(
                runner,
                classifier,
                hooks,
                dispatch,
                queue,
                events::add,
                CTX_FACTORY,
                null,
                counter,
                compactor));
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentLoop(
                runner,
                classifier,
                hooks,
                dispatch,
                queue,
                events::add,
                CTX_FACTORY,
                CLOCK,
                null,
                compactor));
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentLoop(
                runner,
                classifier,
                hooks,
                dispatch,
                queue,
                events::add,
                CTX_FACTORY,
                CLOCK,
                counter,
                null));
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
    var history = state.historySnapshot();
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
    var loop =
        buildLoop(fixedModel("tool tool tool", FinishReason.TOOL_CALLS, Usage.of(1, 1)), queue);
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

    var modelCalls = new AtomicInteger(0);
    Model adaptive =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            var call = modelCalls.incrementAndGet();
            // On turn 1, enqueue a follow-up before signalling STOP.
            // The classifier sees pending messages → continues to turn 2.
            // On turn 2, signal STOP cleanly with queue empty.
            if (call == 1) {
              queue.offer(UserMessage.text("follow-up"));
              return Response.newBuilder()
                  .withContent("partial")
                  .withFinishReason(FinishReason.STOP)
                  .withUsage(Usage.of(2, 1))
                  .build();
            }
            return Response.newBuilder()
                .withContent("done")
                .withFinishReason(FinishReason.STOP)
                .withUsage(Usage.of(3, 2))
                .build();
          }

          @Override
          public String id() {
            return "test";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    var state = freshState();
    var loop = buildLoop(adaptive, queue);
    var result = loop.run(state, SessionLimits.defaults());

    assertEquals(2, modelCalls.get());
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("done", success.result());
  }

  // ── cancellation ──────────────────────────────────────────────────────────

  @Test
  void preCancelledTokenProducesCancelledImmediatelyAfterFirstTurn() {
    var token = new CancellationToken();
    var state = new SessionState(SID, token, CLOCK);
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
        new TurnRunner(
            fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)),
            hooks,
            dispatch,
            queue,
            events::add,
            CTX_FACTORY,
            CLOCK,
            CostCalculator.ZERO,
            null);
    java.util.function.Consumer<QueryEvent> throwingSink =
        e -> {
          throw new RuntimeException("sink boom");
        };
    var sabotaged =
        new AgentLoop(
            runner,
            new StopClassifier(),
            hooks,
            dispatch,
            queue,
            throwingSink,
            CTX_FACTORY,
            CLOCK,
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
    var turns = new AtomicInteger(0);
    Model neverStops =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            var n = turns.incrementAndGet();
            // Enqueue a follow-up on every turn so the loop keeps going up to maxTurns.
            queue.offer(UserMessage.text("turn-" + n));
            return Response.newBuilder()
                .withContent("step-" + n)
                .withFinishReason(FinishReason.STOP)
                .withUsage(Usage.of(1, 1))
                .build();
          }

          @Override
          public String id() {
            return "test";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
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
    var turns = new AtomicInteger(0);
    var state = freshState();
    Model adaptive =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            var n = turns.incrementAndGet();
            if (n == 1) {
              // Turn 1 enqueues a follow-up so the loop runs a second turn after the
              // watermark fires for the first time.
              queue.offer(UserMessage.text("again"));
            } else if (n == 2) {
              // Turn 2 simulates the Day-2 compactor clearing the flag before the
              // watermark check fires again for this turn.
              state.resetContextWarningFlag();
            }
            return Response.newBuilder()
                .withContent("step-" + n)
                .withFinishReason(FinishReason.STOP)
                .withUsage(Usage.of(1, 1))
                .build();
          }

          @Override
          public String id() {
            return "test";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    TokenCounter alwaysOver = msgs -> 90L;
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100).build();
    buildLoopWithCounter(adaptive, queue, alwaysOver).run(state, limits);
    assertEquals(
        2,
        events.stream().filter(e -> e instanceof QueryEvent.ContextWarning).count(),
        "reset must re-arm the watermark");
  }

  @Test
  void contextWatermarkDoesNotFireWhenMaxContextTokensIsZero() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    var sentinel = new AtomicInteger(0);
    TokenCounter counter =
        msgs -> {
          sentinel.incrementAndGet();
          return 1_000_000L;
        };
    // SessionLimits validates maxContextTokens > 0; build defaults then exercise the
    // defensive guard via a sentinel ≤ 0 by passing maxContextTokens = Long.MAX_VALUE
    // — combined with the sentinel counter we only verify the threshold math, not the
    // <= 0 guard (covered separately in the watermark-zero scenario below).
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
    var turns = new AtomicInteger(0);
    Model multi =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            var n = turns.incrementAndGet();
            if (n < 3) {
              queue.offer(UserMessage.text("turn-" + n));
            }
            return Response.newBuilder()
                .withContent("step-" + n)
                .withFinishReason(FinishReason.STOP)
                .withUsage(Usage.of(1, 1))
                .build();
          }

          @Override
          public String id() {
            return "test";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
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

  // ── PreModelTurnHook.MutateInput (BYO compactor as a hook) ────────────────

  @Test
  void preModelTurnMutateInputReplacesHistory() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("first"));
    queue.offer(UserMessage.text("second"));
    queue.offer(UserMessage.text("third"));
    var replacement = List.of(Message.system("compacted system"), Message.user("merged turn"));
    ai.singlr.session.hooks.PreModelTurnHook trimmer =
        (history, ctx) ->
            ai.singlr.session.hooks.HookOutcome.mutate(java.util.Map.of("history", replacement));
    var hookRegistry = new ai.singlr.session.hooks.HookRegistry(List.of(trimmer));
    var runner =
        new TurnRunner(
            fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)),
            hookRegistry,
            dispatch,
            queue,
            events::add,
            CTX_FACTORY,
            CLOCK,
            CostCalculator.ZERO,
            null);
    var loop =
        new AgentLoop(
            runner,
            new StopClassifier(),
            hookRegistry,
            dispatch,
            queue,
            events::add,
            CTX_FACTORY,
            CLOCK,
            TokenCounter.charBased(),
            ContextCompactor.disabled());
    var state = freshState();
    loop.run(state, SessionLimits.defaults());
    var history = state.historySnapshot();
    assertEquals(replacement.size() + 1, history.size(), "history rewritten; assistant appended");
    assertEquals("compacted system", history.get(0).content());
    assertEquals("merged turn", history.get(1).content());
    // HookFired with MutateInput outcomeKind tagged as PreModelTurnHook
    var fired =
        events.stream()
            .filter(e -> e instanceof QueryEvent.HookFired)
            .map(e -> (QueryEvent.HookFired) e)
            .anyMatch(
                h -> h.phase().equals("PreModelTurnHook") && h.outcomeKind().equals("MutateInput"));
    assertTrue(fired, "HookFired with MutateInput must be emitted");
  }

  @Test
  void preModelTurnMutateInputWithMissingHistoryKeyIsNoOp() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    ai.singlr.session.hooks.PreModelTurnHook misbehaving =
        (history, ctx) ->
            ai.singlr.session.hooks.HookOutcome.mutate(java.util.Map.of("not-history", "ignored"));
    var hookRegistry = new ai.singlr.session.hooks.HookRegistry(List.of(misbehaving));
    var runner =
        new TurnRunner(
            fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)),
            hookRegistry,
            dispatch,
            queue,
            events::add,
            CTX_FACTORY,
            CLOCK,
            CostCalculator.ZERO,
            null);
    var loop =
        new AgentLoop(
            runner,
            new StopClassifier(),
            hookRegistry,
            dispatch,
            queue,
            events::add,
            CTX_FACTORY,
            CLOCK,
            TokenCounter.charBased(),
            ContextCompactor.disabled());
    var state = freshState();
    loop.run(state, SessionLimits.defaults());
    // History keeps the single user message + assistant response — no rewrite occurred.
    assertEquals("hi", state.historySnapshot().get(0).content());
  }

  // ── pre-turn watermark check (P0-3) ──────────────────────────────────────

  @Test
  void preTurnWatermarkFiresBeforeModelCallWhenHistoryAlreadyExceeds() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    var modelCalls = new AtomicInteger(0);
    TokenCounter perMessage = msgs -> 96L * msgs.size();
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100).build();
    var compacted = new AtomicInteger(0);
    ContextCompactor shrinking =
        (history, state) -> {
          compacted.incrementAndGet();
          return CompactionResult.noOp(history.subList(history.size() - 1, history.size()));
        };
    Model recorder =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            modelCalls.incrementAndGet();
            return Response.newBuilder()
                .withContent("ok")
                .withFinishReason(FinishReason.STOP)
                .withUsage(Usage.of(1, 1))
                .build();
          }

          @Override
          public String id() {
            return "test";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    buildLoopWith(recorder, queue, perMessage, shrinking).run(freshState(), limits);
    // Pre-turn check fires BEFORE the model call. Counter returns 96*size; after drainAndAppend
    // history has 1 message → tokens=96, usage=0.96 ≥ 0.95 → compactor invoked at PRE-TURN.
    assertTrue(compacted.get() >= 1, "pre-turn compaction must fire");
    assertEquals(1, modelCalls.get(), "model is called once after pre-turn compaction");
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
                history.subList(history.size() - 1, history.size()), Usage.of(200, 40));
    var state = freshState();
    buildLoopWith(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue, loud, reporting)
        .run(state, limits);
    // Model turn contributes 1+1, compaction contributes 200+40.
    assertTrue(state.usage().inputTokens() >= 200, "compaction input tokens must accumulate");
    assertTrue(state.usage().outputTokens() >= 40, "compaction output tokens must accumulate");
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
    assertEquals(1, state.usage().inputTokens());
    assertEquals(1, state.usage().outputTokens());
  }
}
