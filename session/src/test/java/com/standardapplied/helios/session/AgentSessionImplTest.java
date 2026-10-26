/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.BlockingModel;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

final class AgentSessionImplTest {

  private static final String SID = "sess-impl-1";
  private static final Instant FIXED = Instant.parse("2026-05-14T19:00:00Z");
  private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);

  private static AgentSession buildSession(Model model) {
    return buildSession(model, 256);
  }

  private static AgentSession buildSession(Model model, int maxQueuedUserMessages) {
    return AgentSession.create(
        SessionOptions.newBuilder()
            .withModel(model)
            .withSessionId(SID)
            .withMaxQueuedUserMessages(maxQueuedUserMessages)
            .withClock(CLOCK)
            .build());
  }

  private static Model textOnceModel(String reply, FinishReason finishReason) {
    return respondingWith(
        Response.newBuilder()
            .withContent(reply)
            .withFinishReason(finishReason)
            .withUsage(Usage.of(3, 2))
            .build());
  }

  private static Model respondingWith(Response<Void> response) {
    return ScriptedModel.newBuilder().withResponseTurn(response).build();
  }

  private static List<Class<?>> eventTypes(EventCollector sub) {
    return sub.events().stream().<Class<?>>map(QueryEvent::getClass).toList();
  }

  private static ResultMessage terminalOf(AgentSession session) {
    return Await.value("the session to reach its terminal", session.result());
  }

  // ── construction validation ───────────────────────────────────────────────

  @Test
  void constructorRejectsNullOptions() {
    var ex = assertThrows(NullPointerException.class, () -> new AgentSessionImpl(null));
    assertEquals("options must not be null", ex.getMessage());
  }

  @Test
  void createFactoryRejectsNullOptions() {
    var ex = assertThrows(NullPointerException.class, () -> AgentSession.create(null));
    assertEquals("options must not be null", ex.getMessage());
  }

  @Test
  void createFactoryReturnsAgentSession() {
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("x", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .build())) {
      assertEquals(SID, s.sessionId());
    }
  }

  // ── accessor smoke ────────────────────────────────────────────────────────

  @Test
  void sessionIdAccessorReturnsOptionsValue() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      assertEquals(SID, s.sessionId());
    }
  }

  @Test
  void currentTurnIndexStartsAtZero() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      assertEquals(0, s.currentTurnIndex());
    }
  }

  @Test
  void aSessionIsNotStartedUntilItsFirstMessage() throws Exception {
    var s = buildSession(textOnceModel("done", FinishReason.STOP));
    try {
      assertFalse(s.isStarted());
      s.send(UserMessage.text("hi"));
      assertTrue(s.isStarted());
      terminalOf(s);
    } finally {
      s.close();
    }
  }

  @Test
  void anInterruptStartsTheSession() {
    try (var s = buildSession(textOnceModel("done", FinishReason.STOP))) {
      s.interrupt("now");
      assertTrue(s.isStarted());
    }
  }

  @Test
  void aSessionClosedBeforeItsFirstMessageCountsAsStarted() {
    var s = buildSession(textOnceModel("x", FinishReason.STOP));
    s.close();
    assertTrue(s.isStarted());
  }

  @Test
  void eventsAccessorReturnsPublisher() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      assertNotNull(s.events());
    }
  }

  // ── send validation ──────────────────────────────────────────────────────

  @Test
  void sendRejectsNullMessage() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      var ex = assertThrows(NullPointerException.class, () -> s.send((UserMessage) null));
      assertEquals("message must not be null", ex.getMessage());
    }
  }

  @Test
  void sendOnClosedSessionThrows() {
    var s = buildSession(textOnceModel("x", FinishReason.STOP));
    s.close();
    var ex = assertThrows(IllegalStateException.class, () -> s.send(UserMessage.text("hi")));
    assertEquals("session is closed", ex.getMessage());
  }

  @Test
  void sendOnTerminalSessionThrows() throws Exception {
    var s = buildSession(textOnceModel("done", FinishReason.STOP));
    s.send(UserMessage.text("hi"));
    terminalOf(s);
    var ex = assertThrows(IllegalStateException.class, () -> s.send(UserMessage.text("again")));
    assertEquals("session is terminal", ex.getMessage());
    s.close();
  }

  @Test
  void sendFullQueueThrows() throws Exception {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    Model latched = latchedModel(entered, release, "ok");
    var s = buildSession(latched, 2);
    try {
      s.send(UserMessage.text("first"));
      Await.latch("the loop to reach chat()", entered);
      s.send(UserMessage.text("second"));
      s.send(UserMessage.text("third"));
      var ex =
          assertThrows(SteeringQueueFullException.class, () -> s.send(UserMessage.text("fourth")));
      assertEquals("steering queue full at capacity 2", ex.getMessage());
    } finally {
      release.countDown();
      s.close();
    }
  }

  @Test
  void interruptOnFullQueueThrows() throws Exception {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    Model latched = latchedModel(entered, release, "ok");
    var s = buildSession(latched, 1);
    try {
      s.send(UserMessage.text("first"));
      Await.latch("the loop to reach chat()", entered);
      s.send(UserMessage.text("second"));
      var ex = assertThrows(SteeringQueueFullException.class, () -> s.interrupt("nope"));
      assertEquals("steering queue full at capacity 1 — cannot enqueue interrupt", ex.getMessage());
    } finally {
      release.countDown();
      s.close();
    }
  }

  // ── interrupt validation ─────────────────────────────────────────────────

  @Test
  void interruptRejectsNullReason() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      var ex = assertThrows(NullPointerException.class, () -> s.interrupt(null));
      assertEquals("reason must not be null", ex.getMessage());
    }
  }

  @Test
  void interruptRejectsBlankReason() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      var ex = assertThrows(IllegalArgumentException.class, () -> s.interrupt("  "));
      assertEquals("reason must not be blank", ex.getMessage());
    }
  }

  @Test
  void interruptOnClosedSessionThrows() {
    var s = buildSession(textOnceModel("x", FinishReason.STOP));
    s.close();
    assertThrows(IllegalStateException.class, () -> s.interrupt("nope"));
  }

  @Test
  void interruptOnTerminalSessionThrows() throws Exception {
    var s = buildSession(textOnceModel("done", FinishReason.STOP));
    s.send(UserMessage.text("hi"));
    terminalOf(s);
    assertThrows(IllegalStateException.class, () -> s.interrupt("late"));
    s.close();
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void singleMessageProducesSuccessAndStreamCompletes() throws Exception {
    try (var s = buildSession(textOnceModel("hello back", FinishReason.STOP))) {
      var sub = new EventCollector();
      sub.read(s.events());
      s.send(UserMessage.text("hi"));

      var result = terminalOf(s);
      sub.awaitDone();

      var success = assertInstanceOf(ResultMessage.Success.class, result);
      assertEquals("hello back", success.result());
      assertTrue(sub.events().stream().anyMatch(e -> e instanceof QueryEvent.UserMessageReceived));
      assertTrue(sub.events().stream().anyMatch(e -> e instanceof QueryEvent.AssistantText));
      assertTrue(sub.events().stream().anyMatch(e -> e instanceof QueryEvent.LoopEnded));
    }
  }

  @Test
  void runBlockingDrivesSendAndAwait() {
    try (var s = buildSession(textOnceModel("done", FinishReason.STOP))) {
      var result = s.runBlocking(UserMessage.text("hi"));
      var success = assertInstanceOf(ResultMessage.Success.class, result);
      assertEquals("done", success.result());
    }
  }

  // ── interrupt steering ────────────────────────────────────────────────────

  @Test
  void interruptQueuesSyntheticMessageAndContinues() throws Exception {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var firstTurnEntered = new CountDownLatch(1);
    var firstTurnRelease = new CountDownLatch(1);
    Model alternating =
        new BlockingModel() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            var call = calls.incrementAndGet();
            if (call == 1) {
              // Hold the first turn until interrupt() is queued: a first turn that completes
              // with an empty queue sends the session terminal, and interrupt() then throws.
              firstTurnEntered.countDown();
              try {
                firstTurnRelease.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
            return Response.newBuilder()
                .withContent("turn-" + call)
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
    try (var s = buildSession(alternating)) {
      var sub = new EventCollector();
      sub.read(s.events());
      s.send(UserMessage.text("first"));
      Await.latch("the first turn to reach chat()", firstTurnEntered);
      s.interrupt("rethink");
      firstTurnRelease.countDown();
      var result = terminalOf(s);
      sub.awaitDone();

      var success = assertInstanceOf(ResultMessage.Success.class, result);
      var interruptedReceived =
          sub.events().stream()
              .filter(e -> e instanceof QueryEvent.UserMessageReceived)
              .map(e -> (QueryEvent.UserMessageReceived) e)
              .anyMatch(u -> u.message().text().contains("[interrupted by user: rethink]"));
      assertTrue(interruptedReceived, "interrupt synthetic message must be received");
      assertEquals("turn-2", success.result());
      assertEquals(2, calls.get());
    }
  }

  // ── close lifecycle ───────────────────────────────────────────────────────

  @Test
  void closeBeforeAnySendProducesCancelledTerminal() throws Exception {
    var s = buildSession(textOnceModel("never", FinishReason.STOP));
    s.close();
    var result = terminalOf(s);
    var c = assertInstanceOf(ResultMessage.Cancelled.class, result);
    assertEquals("session closed", c.reason());
  }

  @Test
  void closeIsIdempotent() throws Exception {
    var s = buildSession(textOnceModel("x", FinishReason.STOP));
    s.close();
    s.close();
    s.close();
    assertInstanceOf(ResultMessage.Cancelled.class, terminalOf(s));
  }

  @Test
  void closeAfterTerminalDoesNotBreak() throws Exception {
    var s = buildSession(textOnceModel("done", FinishReason.STOP));
    s.send(UserMessage.text("hi"));
    var first = terminalOf(s);
    s.close();
    assertEquals(first, terminalOf(s));
  }

  @Test
  void closeDuringRunningLoopProducesCancelled() {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var s = buildSession(latchedModel(entered, release, "too late"))) {
      s.send(UserMessage.text("hi"));
      Await.latch("the loop to reach chat()", entered);
      s.close();
      release.countDown();
      var cancelled = assertInstanceOf(ResultMessage.Cancelled.class, terminalOf(s));
      assertEquals("session closed", cancelled.reason());
    }
  }

  @Test
  void closeBeforeAnySendEndsEveryOpenStream() {
    var s = (AgentSessionImpl) buildSession(textOnceModel("x", FinishReason.STOP));
    var stream = s.events();
    s.close();
    assertEquals(0, s.lifecycle.events().openStreams(), "close() detaches every stream");
    assertFalse(stream.iterator().hasNext(), "a stream ends with a pre-start close");
  }

  @Test
  void naturalLoopTerminationEndsEveryOpenStreamBeforeTheResultSettles() {
    var s = (AgentSessionImpl) buildSession(textOnceModel("done", FinishReason.STOP));
    s.events();
    s.send(UserMessage.text("hi"));
    terminalOf(s);
    assertEquals(0, s.lifecycle.events().openStreams(), "the teardown ended every stream");
  }

  @Test
  void resultFutureExposedAsCompletableFuture() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      var f = s.result();
      assertInstanceOf(CompletableFuture.class, f);
    }
  }

  @Test
  void modelThatThrowsSynchronouslyProducesErrorTerminal() throws Exception {
    Model throwing =
        new BlockingModel() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new RuntimeException("model boom");
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
    try (var s = buildSession(throwing)) {
      var sub = new EventCollector();
      sub.read(s.events());
      s.send(UserMessage.text("hi"));
      var result = terminalOf(s);
      assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result);
    }
  }

  @Test
  void aHooksErrorFailsTheResultWithThatErrorAfterTheOnlyModelCall() {
    var sentinel = new AssertionError("simulated unrecoverable error");
    PreStopHook erroring =
        (response, ctx) -> {
          throw sentinel;
        };
    var model = ScriptedModel.newBuilder().withTextTurn("done").withTextTurn("never").build();
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(model)
                .withSessionId(SID)
                .withClock(CLOCK)
                .withHook(erroring)
                .build())) {
      s.send(UserMessage.text("hi"));

      assertSame(sentinel, Await.failure("the result future to fail", s.result()));
    }
    assertEquals(1, model.calls().size());
  }

  @Test
  void systemPromptLeadsTheHistoryTheModelSees() {
    var model = ScriptedModel.newBuilder().withTextTurn("ok").build();
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(model)
                .withSessionId(SID)
                .withClock(CLOCK)
                .withSystemPrompt("be terse")
                .build())) {
      s.runBlocking(UserMessage.text("hi"));
    }
    var seen = model.calls().getFirst();
    assertEquals(List.of("be terse", "hi"), seen.stream().map(Message::content).toList());
    assertEquals(com.standardapplied.helios.core.model.Role.SYSTEM, seen.getFirst().role());
  }

  @Test
  void multipleSubscribersAllReceiveEvents() throws Exception {
    try (var s = buildSession(textOnceModel("hello", FinishReason.STOP))) {
      var sub1 = new EventCollector();
      var sub2 = new EventCollector();
      sub1.read(s.events());
      sub2.read(s.events());
      s.send(UserMessage.text("hi"));
      terminalOf(s);
      sub1.awaitDone();
      sub2.awaitDone();
      assertEquals(
          List.of(
              QueryEvent.UserMessageReceived.class,
              QueryEvent.AssistantText.class,
              QueryEvent.TurnEnded.class,
              QueryEvent.LoopEnded.class),
          eventTypes(sub1));
      assertEquals(sub1.events(), sub2.events(), "both subscribers see the same stream");
    }
  }

  @Test
  void resultStaysPendingWhileTheModelIsBlocked() {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var s = buildSession(latchedModel(entered, release, "late"))) {
      s.send(UserMessage.text("hi"));
      Await.latch("the loop to reach chat()", entered);
      assertFalse(s.result().isDone(), "the result cannot settle while the model call is open");
      release.countDown();
      var success = assertInstanceOf(ResultMessage.Success.class, terminalOf(s));
      assertEquals("late", success.result());
    }
  }

  @Test
  void timestampOnPublishedEventsCarriesClock() throws Exception {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      var sub = new EventCollector();
      sub.read(s.events());
      s.send(UserMessage.text("hi"));
      terminalOf(s);
      sub.awaitDone();
      for (var e : sub.events()) {
        assertEquals(FIXED, e.timestamp());
      }
    }
  }

  // ── provider-reported refusal detail and thinking reach the session surface ──

  @Test
  void refusalTerminalCarriesTheProviderCategoryAndExplanation() {
    var declined =
        Response.newBuilder()
            .withContent("")
            .withFinishReason(FinishReason.REFUSAL)
            .withUsage(Usage.of(412, 0))
            .withMetadata(
                Map.of(
                    Response.REFUSAL_CATEGORY_KEY,
                    "cyber",
                    Response.REFUSAL_EXPLANATION_KEY,
                    "This request was declined because it could enable cyber harm."))
            .build();

    try (var s = buildSession(respondingWith(declined))) {
      var refusal =
          assertInstanceOf(ResultMessage.Refusal.class, s.runBlocking(UserMessage.text("hi")));

      assertEquals("cyber", refusal.category());
      assertEquals(
          "This request was declined because it could enable cyber harm.", refusal.refusalText());
      assertEquals(412, refusal.usage().inputTokens());
    }
  }

  @Test
  void thinkingFromABlockingModelSurfacesAsAssistantThinkingBeforeTheText() throws Exception {
    var thoughtful =
        Response.newBuilder()
            .withContent("Top match: profile 12.")
            .withThinking("Ranked the candidates. Reading profile 12 next.")
            .withFinishReason(FinishReason.STOP)
            .withUsage(Usage.of(3, 2))
            .build();

    try (var s = buildSession(respondingWith(thoughtful))) {
      var sub = new EventCollector();
      sub.read(s.events());
      s.send(UserMessage.text("match me"));
      terminalOf(s);
      sub.awaitDone();

      var kinds =
          sub.events().stream()
              .filter(
                  e ->
                      e instanceof QueryEvent.AssistantThinking
                          || e instanceof QueryEvent.AssistantText)
              .toList();
      assertEquals(2, kinds.size());
      var thinking = assertInstanceOf(QueryEvent.AssistantThinking.class, kinds.get(0));
      assertEquals("Ranked the candidates. Reading profile 12 next.", thinking.text());
      assertInstanceOf(QueryEvent.AssistantText.class, kinds.get(1));
    }
  }

  // ── maxBudgetMicroUsd integration ────────────────────────────────────────

  @Test
  void costCalculatorAccumulatesAndExceedingBudgetProducesErrorMaxBudgetTerminal() {
    // Pricing: $1.00 / Mtok input, $1.00 / Mtok output. One turn of 1M+1M tokens => $2.00.
    // Budget: $1.50. Expectation: ErrorMaxBudgetUsd terminal with microUsdSpent == 2_000_000.
    var calc =
        CostCalculator.staticTable(
            Map.of("test", CostCalculator.Pricing.ofUsdPerMillion(1.0, 1.0)));
    var limits = SessionLimits.newBuilder().withMaxBudgetMicroUsd(1_500_000L).build();
    var bigUsage = Usage.of(1_000_000, 1_000_000);
    var billy =
        ScriptedModel.newBuilder()
            .withId("test")
            .withResponseTurn(
                Response.newBuilder()
                    .withContent("here")
                    .withFinishReason(FinishReason.STOP)
                    .withUsage(bigUsage)
                    .build())
            .build();
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(billy)
                .withSessionId(SID)
                .withClock(CLOCK)
                .withLimits(limits)
                .withCostCalculator(calc)
                .build())) {
      var terminal = s.runBlocking(UserMessage.text("hi"));
      var budget = assertInstanceOf(ResultMessage.ErrorMaxBudgetUsd.class, terminal);
      assertEquals(2_000_000L, budget.microUsdSpent());
      assertEquals(2_000_000L, budget.cost().microUsd());
    }
  }

  @Test
  void defaultCostCalculatorIsZeroSoBudgetNeverFires() {
    // No withCostCalculator(...) call. Even with a tight budget, cost stays $0 and the session
    // completes normally — this is the opt-in contract.
    var limits = SessionLimits.newBuilder().withMaxBudgetMicroUsd(10_000L).build();
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("hello", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .withLimits(limits)
                .build())) {
      var terminal = s.runBlocking(UserMessage.text("hi"));
      assertInstanceOf(ResultMessage.Success.class, terminal);
      assertEquals(CostEstimate.zero(), terminal.cost());
    }
  }

  // ── answer() validation ──────────────────────────────────────────────────

  @Test
  void answerRejectsNullQuestionId() {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      assertThrows(
          NullPointerException.class,
          () -> s.answer(null, AskUserQuestionResponse.single("q", "ok")));
    }
  }

  @Test
  void answerRejectsBlankQuestionId() {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> s.answer("  ", AskUserQuestionResponse.single("q", "ok")));
    }
  }

  @Test
  void answerRejectsNullResponse() {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      assertThrows(NullPointerException.class, () -> s.answer("q-1", null));
    }
  }

  @Test
  void answerRejectsMismatchedResponseQuestionId() {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      var ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> s.answer("q-1", AskUserQuestionResponse.single("q-OTHER", "x")));
      assertTrue(ex.getMessage().contains("does not match"));
    }
  }

  @Test
  void answerOnClosedSessionThrows() {
    var s = buildSession(textOnceModel("hi", FinishReason.STOP));
    s.close();
    assertThrows(
        IllegalStateException.class,
        () -> s.answer("q-1", AskUserQuestionResponse.single("q-1", "ok")));
  }

  @Test
  void answerForUnknownQuestionIdThrows() {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      var ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> s.answer("q-unknown", AskUserQuestionResponse.single("q-unknown", "ok")));
      assertTrue(ex.getMessage().contains("no pending question"));
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** Model whose chat() awaits {@code release} after signalling {@code entered}. */
  private static Model latchedModel(CountDownLatch entered, CountDownLatch release, String reply) {
    return new BlockingModel() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        entered.countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return Response.newBuilder()
            .withContent(reply)
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
  }
}
