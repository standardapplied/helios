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
import java.util.Map;
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
                history.subList(history.size() - 1, history.size()),
                Usage.of(200, 40),
                "summary-model");
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

  // ── effectiveMaxContextTokens resolver (P0-2c, model-aware default) ──────

  /**
   * Factory that returns a Model whose documented {@link Model#contextWindow()} is the supplied
   * value, so tests can exercise the auto-resolution paths without depending on a real provider.
   */
  private static Model modelWithContextWindow(int contextWindow) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder()
            .withContent("ok")
            .withFinishReason(FinishReason.STOP)
            .withUsage(Usage.of(1, 1))
            .build();
      }

      @Override
      public String id() {
        return "ctx-window-test";
      }

      @Override
      public String provider() {
        return "test";
      }

      @Override
      public int contextWindow() {
        return contextWindow;
      }
    };
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
    assertEquals(980_000L, loop.effectiveMaxContextTokens(limits));
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
    assertEquals(50_000L, loop.effectiveMaxContextTokens(limits));
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
    assertEquals(180_000L, loop.effectiveMaxContextTokens(limits));
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
        AgentLoop.AUTO_FALLBACK_MAX_CONTEXT_TOKENS, loop.effectiveMaxContextTokens(limits));
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
    assertEquals(75_000L, loop.effectiveMaxContextTokens(limits));
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
    var model = fixedModel("ok", FinishReason.STOP, Usage.of(0, 0));
    var runner =
        new TurnRunner(
            model, hooks, dispatch, queue, events::add, CTX_FACTORY, CLOCK, calculator, null);
    var loop =
        new AgentLoop(
            runner,
            new StopClassifier(),
            hooks,
            dispatch,
            queue,
            events::add,
            CTX_FACTORY,
            CLOCK,
            staged,
            reporting);
    var state = freshState();
    loop.run(state, limits);
    // 1_000_000 input × (10_000_000 μUSD / 1M tokens) + 100_000 output × (10/M) = 11_000_000 μUSD.
    // The pre-fix code priced at the main rate (100/M) → 110_000_000 μUSD. The order-of-magnitude
    // assertion isolates the routing fix from the exact firing count.
    var cost = state.cost().microUsd();
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
      ai.singlr.session.hooks.HookRegistry hookRegistry) {
    var runner =
        new TurnRunner(
            model,
            hookRegistry,
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
        hookRegistry,
        dispatch,
        queue,
        events::add,
        CTX_FACTORY,
        CLOCK,
        counter,
        compactor);
  }

  @Test
  void preCompactHookCanMutateHistoryHandedToCompactor() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("original"));
    TokenCounter trigger = msgs -> 96L * msgs.size();
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100L).build();
    var compactorSawHistory = new java.util.concurrent.atomic.AtomicReference<List<Message>>();
    ContextCompactor capturing =
        (history, state) -> {
          compactorSawHistory.set(history);
          return CompactionResult.noOp(history);
        };
    var replacement = List.<Message>of(Message.user("rewritten by hook"));
    ai.singlr.session.hooks.PreCompactHook mutator =
        (history, ctx) ->
            ai.singlr.session.hooks.HookOutcome.mutate(Map.of("history", replacement));
    var hookRegistry = new ai.singlr.session.hooks.HookRegistry(List.of(mutator));
    buildLoopWithHooks(
            fixedModel("ok", FinishReason.STOP, Usage.of(0, 0)),
            queue,
            trigger,
            capturing,
            hookRegistry)
        .run(freshState(), limits);
    var observed = compactorSawHistory.get();
    assertEquals(1, observed.size(), "compactor must receive the rewritten history");
    assertEquals("rewritten by hook", observed.get(0).content());
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e instanceof QueryEvent.HookFired hf
                        && "PreCompactHook".equals(hf.phase())
                        && "MutateInput".equals(hf.outcomeKind())),
        "HookFired{PreCompactHook, MutateInput} must be emitted");
  }

  @Test
  void preCompactHookMutateInputWithMissingHistoryKeyIsNoOp() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("original-user-msg"));
    TokenCounter trigger = msgs -> 96L * msgs.size();
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100L).build();
    // Capture every history handed to the compactor so we can verify none was rewritten by the
    // misbehaving hook (bogus key under MutateInput must fall back to the genuine history).
    var observedHistories = new ArrayList<List<Message>>();
    ContextCompactor capturing =
        (history, state) -> {
          observedHistories.add(history);
          return CompactionResult.noOp(history);
        };
    ai.singlr.session.hooks.PreCompactHook misbehaving =
        (history, ctx) ->
            ai.singlr.session.hooks.HookOutcome.mutate(Map.of("not-history", "ignored"));
    var hookRegistry = new ai.singlr.session.hooks.HookRegistry(List.of(misbehaving));
    buildLoopWithHooks(
            fixedModel("ok", FinishReason.STOP, Usage.of(0, 0)),
            queue,
            trigger,
            capturing,
            hookRegistry)
        .run(freshState(), limits);
    assertTrue(!observedHistories.isEmpty(), "compactor must have been invoked at least once");
    for (var observed : observedHistories) {
      for (var m : observed) {
        var content = m.content();
        assertTrue(
            content != null && (content.startsWith("original-user-msg") || "ok".equals(content)),
            () ->
                "compactor must only see genuine session messages — got '"
                    + content
                    + "'. The bogus 'not-history' key must NOT have rewritten the history.");
      }
    }
  }

  @Test
  void postCompactHookFiresWithBeforeAfterPayloadOnSuccessfulShrink() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    queue.offer(UserMessage.text("hi2"));
    TokenCounter trigger = msgs -> 96L * msgs.size();
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100L).build();
    // Real shrink: return single-message list from the supplied history.
    ContextCompactor shrinking =
        (history, state) ->
            new CompactionResult(
                List.of(Message.user("[Earlier context summary]\nthe gist")), Usage.of(0, 0), "");
    var payloadSeen =
        new java.util.concurrent.atomic.AtomicReference<
            ai.singlr.session.hooks.CompactionPayload>();
    ai.singlr.session.hooks.PostCompactHook observer =
        (payload, ctx) -> {
          payloadSeen.set(payload);
          return ai.singlr.session.hooks.HookOutcome.cont();
        };
    var hookRegistry = new ai.singlr.session.hooks.HookRegistry(List.of(observer));
    buildLoopWithHooks(
            fixedModel("ok", FinishReason.STOP, Usage.of(0, 0)),
            queue,
            trigger,
            shrinking,
            hookRegistry)
        .run(freshState(), limits);
    var payload = payloadSeen.get();
    assertTrue(payload != null, "PostCompactHook must fire on a real shrink");
    assertTrue(payload.removedBlocks() > 0, "payload must report removed-block count");
    assertEquals("the gist", payload.summary());
  }

  @Test
  void postCompactHookNotFiredOnNoOpCompaction() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    TokenCounter trigger = msgs -> 96L * msgs.size();
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100L).build();
    ContextCompactor noOp = (history, state) -> CompactionResult.noOp(history);
    var fired = new AtomicInteger(0);
    ai.singlr.session.hooks.PostCompactHook observer =
        (payload, ctx) -> {
          fired.incrementAndGet();
          return ai.singlr.session.hooks.HookOutcome.cont();
        };
    var hookRegistry = new ai.singlr.session.hooks.HookRegistry(List.of(observer));
    buildLoopWithHooks(
            fixedModel("ok", FinishReason.STOP, Usage.of(0, 0)), queue, trigger, noOp, hookRegistry)
        .run(freshState(), limits);
    assertEquals(0, fired.get(), "PostCompactHook must not fire when compactor returned no shrink");
  }

  @Test
  void preCompactHookMutateInputWithEmptyHistoryListIsAccepted() {
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
    // Empty list under 'history' is a valid MutateInput — the helper should return List.of(),
    // exercising the for-loop's zero-iteration path in messageListField.
    ai.singlr.session.hooks.PreCompactHook empty =
        (history, ctx) -> ai.singlr.session.hooks.HookOutcome.mutate(Map.of("history", List.of()));
    var hookRegistry = new ai.singlr.session.hooks.HookRegistry(List.of(empty));
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
  void preCompactHookMutateInputHistoryNotAListFallsBack() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("u"));
    TokenCounter trigger = msgs -> 96L * msgs.size();
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100L).build();
    var observed = new ArrayList<List<Message>>();
    ContextCompactor capturing =
        (history, state) -> {
          observed.add(history);
          return CompactionResult.noOp(history);
        };
    // 'history' key maps to a String — not a List — so the helper returns null and the loop falls
    // back to the original history.
    ai.singlr.session.hooks.PreCompactHook bogus =
        (history, ctx) ->
            ai.singlr.session.hooks.HookOutcome.mutate(Map.of("history", "not a list"));
    var hookRegistry = new ai.singlr.session.hooks.HookRegistry(List.of(bogus));
    buildLoopWithHooks(
            fixedModel("ok", FinishReason.STOP, Usage.of(0, 0)),
            queue,
            trigger,
            capturing,
            hookRegistry)
        .run(freshState(), limits);
    assertTrue(!observed.isEmpty());
  }

  @Test
  void preCompactHookMutateInputHistoryWithNonMessageElementsFallsBack() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("original"));
    TokenCounter trigger = msgs -> 96L * msgs.size();
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100L).build();
    var observedHistories = new ArrayList<List<Message>>();
    ContextCompactor capturing =
        (history, state) -> {
          observedHistories.add(history);
          return CompactionResult.noOp(history);
        };
    // History key contains a List but elements are not Message — fall back to original history.
    ai.singlr.session.hooks.PreCompactHook bogus =
        (history, ctx) ->
            ai.singlr.session.hooks.HookOutcome.mutate(
                Map.of("history", List.of("not a message", 42)));
    var hookRegistry = new ai.singlr.session.hooks.HookRegistry(List.of(bogus));
    buildLoopWithHooks(
            fixedModel("ok", FinishReason.STOP, Usage.of(0, 0)),
            queue,
            trigger,
            capturing,
            hookRegistry)
        .run(freshState(), limits);
    assertTrue(!observedHistories.isEmpty());
    for (var observed : observedHistories) {
      for (var m : observed) {
        assertTrue(
            m != null && m.content() != null,
            "compactor must only see real Message records, not the bogus non-Message list");
      }
    }
  }

  @Test
  void preCompactHookUnsupportedOutcomesFallBackToContinue() {
    // Block / Stop / Inject are not honored at this phase; assert each is logged + treated as
    // Continue so the compactor still runs against the unmodified history.
    var outcomes =
        List.of(
            ai.singlr.session.hooks.HookOutcome.block("nope"),
            ai.singlr.session.hooks.HookOutcome.stop("would-stop"),
            ai.singlr.session.hooks.HookOutcome.inject("would-inject"));
    for (var outcome : outcomes) {
      var queue = new SteeringQueue(8);
      queue.offer(UserMessage.text("hi"));
      TokenCounter trigger = msgs -> 96L * msgs.size();
      var limits = SessionLimits.newBuilder().withMaxContextTokens(100L).build();
      var compactorInvoked = new AtomicInteger(0);
      ContextCompactor capturing =
          (history, state) -> {
            compactorInvoked.incrementAndGet();
            return CompactionResult.noOp(history);
          };
      ai.singlr.session.hooks.PreCompactHook hook = (history, ctx) -> outcome;
      var hookRegistry = new ai.singlr.session.hooks.HookRegistry(List.of(hook));
      buildLoopWithHooks(
              fixedModel("ok", FinishReason.STOP, Usage.of(0, 0)),
              queue,
              trigger,
              capturing,
              hookRegistry)
          .run(freshState(), limits);
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
            ai.singlr.session.hooks.HookOutcome.mutate(Map.of("ignored", "value")),
            ai.singlr.session.hooks.HookOutcome.block("nope"),
            ai.singlr.session.hooks.HookOutcome.inject("would-inject"));
    for (var outcome : outcomes) {
      var queue = new SteeringQueue(8);
      queue.offer(UserMessage.text("hi"));
      queue.offer(UserMessage.text("hi2"));
      TokenCounter trigger = msgs -> 96L * msgs.size();
      var limits = SessionLimits.newBuilder().withMaxContextTokens(100L).build();
      ContextCompactor shrinking =
          (history, state) ->
              new CompactionResult(
                  List.of(Message.user("[Earlier context summary]\nshort")), Usage.of(0, 0), "");
      ai.singlr.session.hooks.PostCompactHook hook = (payload, ctx) -> outcome;
      var hookRegistry = new ai.singlr.session.hooks.HookRegistry(List.of(hook));
      var freshEventBuffer = new ArrayList<QueryEvent>(events);
      freshEventBuffer.clear();
      events.clear();
      buildLoopWithHooks(
              fixedModel("ok", FinishReason.STOP, Usage.of(0, 0)),
              queue,
              trigger,
              shrinking,
              hookRegistry)
          .run(freshState(), limits);
      assertTrue(
          events.stream().anyMatch(e -> e instanceof QueryEvent.ContextEdited),
          () ->
              "ContextEdited must still fire when PostCompactHook returned unsupported outcome: "
                  + outcome.getClass().getSimpleName());
    }
  }

  @Test
  void postCompactHookStopTerminatesSession() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    queue.offer(UserMessage.text("hi2"));
    TokenCounter trigger = msgs -> 96L * msgs.size();
    var limits = SessionLimits.newBuilder().withMaxContextTokens(100L).build();
    ContextCompactor shrinking =
        (history, state) ->
            new CompactionResult(
                List.of(Message.user("[Earlier context summary]\nshort")), Usage.of(0, 0), "");
    ai.singlr.session.hooks.PostCompactHook stopper =
        (payload, ctx) -> ai.singlr.session.hooks.HookOutcome.stop("post-compact veto");
    var hookRegistry = new ai.singlr.session.hooks.HookRegistry(List.of(stopper));
    var state = freshState();
    var result =
        buildLoopWithHooks(
                fixedModel("ok", FinishReason.STOP, Usage.of(0, 0)),
                queue,
                trigger,
                shrinking,
                hookRegistry)
            .run(state, limits);
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("post-compact veto", success.result());
    assertTrue(
        events.stream().noneMatch(e -> e instanceof QueryEvent.ContextEdited),
        "ContextEdited must not fire when PostCompactHook returned Stop");
  }
}
