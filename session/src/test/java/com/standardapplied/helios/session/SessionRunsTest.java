/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.EndingSessions.NOT_REACHED;
import static com.standardapplied.helios.session.EndingSessions.binding;
import static com.standardapplied.helios.session.EndingSessions.holdingItsEnd;
import static com.standardapplied.helios.session.EndingSessions.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import com.standardapplied.helios.session.ask.AskUserQuestionTool;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.OnStreamEventHook;
import com.standardapplied.helios.session.hooks.OnUserMessageHook;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.LogRecord;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A session is a conversation that runs many times: each {@code send} steers the run in progress or
 * starts the next one, every run sees the whole conversation and has its own id, limits, totals and
 * token, a run can follow any ending, {@code interrupt()} cancels the run and keeps the session,
 * and one event stream spans every run.
 */
final class SessionRunsTest {

  private static final String SID = "sess-runs";
  private static final ToolCall ECHO = new ToolCall("c1", "echo", Map.of("v", "one"));
  private static final ToolCall ECHO_TWO = new ToolCall("c2", "echo", Map.of("v", "two"));

  // ── 1. two runs ──────────────────────────────────────────────────────────

  @Test
  void aSecondRunSeesTheWholeConversationAndReportsOnlyItsOwnTotals() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(Usage.of(10, 1), ECHO)
            .withTextTurn("one", Usage.of(5, 1))
            .withTextTurn("two", Usage.of(7, 2))
            .build();
    var options =
        options(model)
            .withSystemPrompt("sys")
            .withTools(List.of(echo()))
            .withCostCalculator((id, usage) -> CostEstimate.ofMicroUsd(usage.inputTokens()));

    try (var session = AgentSession.open(options.build())) {
      var first = Await.value("run 1", session.send("first"));
      var second = Await.value("run 2", session.send("second"));

      var calls = model.calls();
      var expected = new ArrayList<>(calls.get(1));
      expected.add(calls.get(2).get(expected.size()));
      expected.add(Message.user("second"));
      assertEquals(expected, calls.get(2));
      assertEquals(
          List.of(Role.ASSISTANT, "one"),
          List.of(calls.get(2).get(4).role(), calls.get(2).get(4).content()));
      assertEquals(
          List.of(Usage.of(15, 2), CostEstimate.ofMicroUsd(15)),
          List.of(first.usage(), first.cost()));
      assertEquals(
          List.of(Usage.of(7, 2), CostEstimate.ofMicroUsd(7)),
          List.of(second.usage(), second.cost()));
      assertNotEquals(first.runId(), second.runId());
    }
  }

  // ── 2. again after every ending ──────────────────────────────────────────

  static Stream<Arguments> endings() {
    return Stream.of(
        Arguments.of("ErrorMaxTurns", (Function<Fixture, ResultMessage>) Fixture::maxTurns),
        Arguments.of("ErrorMaxBudgetUsd", (Function<Fixture, ResultMessage>) Fixture::budget),
        Arguments.of("ErrorMaxWallClock", (Function<Fixture, ResultMessage>) Fixture::wallClock),
        Arguments.of("Cancelled", (Function<Fixture, ResultMessage>) Fixture::interrupted),
        Arguments.of("ErrorDuringExecution", (Function<Fixture, ResultMessage>) Fixture::failed),
        Arguments.of("Refusal", (Function<Fixture, ResultMessage>) Fixture::refused));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("endings")
  void aSessionRunsAgainAfterEveryEndingFromTurnOne(
      String ending, Function<Fixture, ResultMessage> end) {
    try (var fixture = new Fixture()) {
      var ended = end.apply(fixture);

      var next = Await.value("the next run", fixture.session.send("again"));

      assertEquals(ending, ended.getClass().getSimpleName());
      assertInstanceOf(ResultMessage.Success.class, next);
      assertEquals(1L, fixture.turns.get(fixture.turns.size() - 1));
    }
  }

  // ── 3. limits per run ────────────────────────────────────────────────────

  @Test
  void maxTurnsCountsTheTurnsOfEachRun() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(ECHO)
            .withTextTurn("one")
            .withToolCallsTurn(ECHO)
            .withTextTurn("two")
            .withToolCallsTurn(ECHO)
            .withTextTurn("three")
            .build();
    var options =
        options(model)
            .withTools(List.of(echo()))
            .withLimits(SessionLimits.newBuilder().withMaxTurns(2).build());

    try (var session = AgentSession.open(options.build())) {
      for (var text : List.of("one", "two", "three")) {
        var success =
            assertInstanceOf(ResultMessage.Success.class, Await.value("a run", session.send("go")));
        assertEquals(text, success.result());
      }
    }
  }

  // ── 4. an open round is closed ───────────────────────────────────────────

  @Test
  void aRunAHookStopsBeforeItsCallsLeavesThemAnsweredAsNotRunForTheNextRun() {
    var model =
        ScriptedModel.newBuilder().withToolCallsTurn(ECHO, ECHO_TWO).withTextTurn("after").build();
    PreToolUseHook stopping = (call, ctx) -> HookOutcome.stop("halted");
    var events = new EventCollector();

    try (var session =
        AgentSession.open(options(model).withTools(List.of(echo())).withHook(stopping).build())) {
      events.read(session.events());
      Await.value("run 1", session.send("go"));
      Await.value("run 2", session.send("next"));
    }

    var second = model.calls().get(1);
    assertEquals(
        List.of(notRun(ECHO), notRun(ECHO_TWO), Message.user("next")),
        second.subList(2, second.size()));
    assertEquals(List.of(), events.eventsOf(QueryEvent.ToolResult.class));
  }

  @Test
  void aRunAHookStopsAfterItsFirstCallKeepsThatResultAndAnswersTheRestAsNotRun() {
    var model =
        ScriptedModel.newBuilder().withToolCallsTurn(ECHO, ECHO_TWO).withTextTurn("after").build();
    PostToolUseHook stopping = (call, result, ctx) -> HookOutcome.stop("halted");

    try (var session =
        AgentSession.open(options(model).withTools(List.of(echo())).withHook(stopping).build())) {
      Await.value("run 1", session.send("go"));
      Await.value("run 2", session.send("next"));
    }

    var second = model.calls().get(1);
    assertEquals(
        List.of(Message.tool("c1", "echo", "echoed one"), notRun(ECHO_TWO), Message.user("next")),
        second.subList(2, second.size()));
  }

  // ── 5. steering and the boundary ─────────────────────────────────────────

  @Test
  void aMessageSentWhileAToolRunsJoinsThatRun() {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(new ToolCall("c1", "wait", Map.of()))
            .withTextTurn("done")
            .build();
    var waiting =
        binding(
            "wait",
            (args, ctx) -> {
              entered.countDown();
              Await.latch("the test to release the tool", release);
              return ToolResult.success("waited");
            });
    var events = new EventCollector();

    try (var session = AgentSession.open(options(model).withTools(List.of(waiting)).build())) {
      events.read(session.events());
      var first = session.send("go");
      Await.latch("the tool to run", entered);
      var second = session.send("more");
      release.countDown();

      assertSame(first, second);
      var runId = Await.value("the run", first).runId();
      events.awaitRunEnded();
      assertEquals(1, events.eventsOf(QueryEvent.RunStarted.class).size());
      assertEquals(runId, received(events, "more").runId());
    }
  }

  @Test
  void aMessageSentFromAPreStopHookJoinsTheRunThatHasNotDecidedToEnd() {
    var model = ScriptedModel.newBuilder().withTextTurn("one").withTextTurn("two").build();
    var session = new AtomicReference<AgentSession>();
    var sent = new CompletableFuture<CompletableFuture<ResultMessage>>();
    PreStopHook steering =
        (response, ctx) -> {
          if (!sent.isDone()) {
            sent.complete(session.get().send("more"));
          }
          return HookOutcome.cont();
        };

    try (var open = AgentSession.open(options(model).withHook(steering).build())) {
      session.set(open);
      var run = open.send("go");

      var success = assertInstanceOf(ResultMessage.Success.class, Await.value("the run", run));
      assertSame(run, Await.value("the steered send", sent));
      assertEquals("two", success.result());
    }
  }

  @Test
  void aMessageSentFromAPreStopHookOfARunWithNoTurnLeftEndsItAtItsTurnLimitAndIsDiscarded() {
    var model = ScriptedModel.newBuilder().withTextTurn("one").withTextTurn("two").build();
    var session = new AtomicReference<AgentSession>();
    var events = new EventCollector();
    PreStopHook steering =
        (response, ctx) -> {
          session.get().send("more");
          return HookOutcome.cont();
        };
    var options =
        options(model)
            .withHook(steering)
            .withLimits(SessionLimits.newBuilder().withMaxTurns(1).build());

    try (var open = AgentSession.open(options.build())) {
      session.set(open);
      events.read(open.events());

      assertInstanceOf(ResultMessage.ErrorMaxTurns.class, Await.value("the run", open.send("go")));
      Await.value("the next run", open.send("next"));
      open.close();
      events.awaitDone();
    }
    assertEquals(List.of("go", "next"), receivedTexts(events));
  }

  @Test
  void messagesSentAfterTheRunDecidedToEndStartTheNextRunOnceItEnded() {
    var model = ScriptedModel.newBuilder().withTextTurn("one").withTextTurn("two").build();
    var ending = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var events = new EventCollector();

    try (var session = AgentSession.open(holdingItsEnd(options(model), ending, release).build())) {
      events.read(session.events());
      var first = session.send("go");
      Await.latch("the run to decide its end", ending);
      var next = session.send("next");
      var again = session.send("again");
      release.countDown();

      assertSame(next, again);
      var firstRun = Await.value("run 1", first).runId();
      var nextRun = Await.value("run 2", next).runId();
      assertNotEquals(firstRun, nextRun);
      session.close();
      events.awaitDone();
      assertEquals(
          List.of(firstRun, nextRun, nextRun),
          events.eventsOf(QueryEvent.UserMessageReceived.class).stream()
              .map(QueryEvent::runId)
              .toList());
    }
  }

  @Test
  void aCompletionCallbackOfARunCanStartAndJoinTheNextRun() {
    var model = ScriptedModel.newBuilder().withTextTurn("one").withTextTurn("two").build();
    var ending = new CountDownLatch(1);
    var release = new CountDownLatch(1);

    try (var session = AgentSession.open(holdingItsEnd(options(model), ending, release).build())) {
      var first = session.send("go");
      Await.latch("the run to decide its end", ending);
      var chained = first.thenApply(result -> session.send("next").join());
      release.countDown();

      assertInstanceOf(ResultMessage.Success.class, Await.value("the next run", chained));
      assertEquals(2, model.calls().size());
    }
  }

  @Test
  void aCompletionCallbackOfARunCanJoinTheRunQueuedBehindIt() {
    var model = ScriptedModel.newBuilder().withTextTurn("one").withTextTurn("two").build();
    var ending = new CountDownLatch(1);
    var release = new CountDownLatch(1);

    try (var session = AgentSession.open(holdingItsEnd(options(model), ending, release).build())) {
      var first = session.send("go");
      Await.latch("the run to decide its end", ending);
      var next = session.send("next");
      var chained = first.thenApply(result -> next.join());
      release.countDown();

      assertInstanceOf(ResultMessage.Success.class, Await.value("the next run", chained));
    }
  }

  @Test
  void anInterruptOfTheRunsThreadEndsItsWaitForItsCallbacksEarly() {
    var model = ScriptedModel.newBuilder().withTextTurn("one").build();
    var release = new CountDownLatch(1);
    var logged = new CopyOnWriteArrayList<LogRecord>();
    PreStopHook interrupting =
        (response, ctx) -> {
          var runThread = Thread.currentThread();
          ctx.cancellation()
              .onCancel(
                  () -> {
                    runThread.interrupt();
                    Uninterruptible.await(release);
                  });
          return HookOutcome.cont();
        };
    var options =
        options(model)
            .withHook(interrupting)
            .withLimits(SessionLimits.newBuilder().withStopGrace(NOT_REACHED).build());

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session = AgentSession.open(options.build())) {
      var run = Await.value("the run", session.send("go"));

      assertInstanceOf(ResultMessage.Success.class, run);
      assertEquals(
          List.of(
              "a callback of the cancellation of run "
                  + run.runId()
                  + " of session "
                  + SID
                  + " did not return within PT10M"),
          logged.stream().map(LogRecord::getMessage).toList());
      release.countDown();
    }
  }

  @Test
  void aMessageSentAsTheRunDecidesToEndIsEitherPartOfItOrStartsTheNextRun() {
    for (var i = 0; i < 200; i++) {
      raceTheEndOfARun();
    }
  }

  private static void raceTheEndOfARun() {
    var go = new CountDownLatch(1);
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                held(go, new ModelChunk.TextDelta("one"), stop(FinishReason.STOP.name())))
            .withTextTurn("two")
            .withTextTurn("three")
            .build();
    var events = new EventCollector();
    try (var session = AgentSession.open(options(model).build())) {
      events.read(session.events());
      var first = session.send("go");
      var sending =
          CompletableFuture.supplyAsync(
              () -> {
                Await.latch("the release", go);
                return session.send("late");
              });
      go.countDown();
      var late = Await.value("the late send", sending);
      var lateRun = Await.value("the late message's run", late).runId();
      Await.value("run 1", first);

      Await.value("a third run", session.send("third"));
      session.close();
      events.awaitDone();
      assertEquals(lateRun, received(events, "late").runId());
      assertEquals(List.of("third"), lastRunMessages(events));
    }
  }

  // ── 6. interrupt ─────────────────────────────────────────────────────────

  @Test
  void anInterruptCancelsTheRunClosesItsStreamAndKeepsTheSession() {
    var entered = new CountDownLatch(1);
    var closed = new CountDownLatch(1);
    var stalled = ModelStreams.stalled(entered::countDown);
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                stream(
                    stalled::hasNext,
                    () -> {
                      stalled.close();
                      closed.countDown();
                    }))
            .withTextTurn("after")
            .build();

    try (var session = AgentSession.open(options(model).build())) {
      var run = session.send("go");
      Await.latch("the model stream to be read", entered);
      session.interrupt();

      var cancelled = assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the run", run));
      assertEquals("interrupted", cancelled.reason());
      Await.latch("the stream to close", closed);
      assertInstanceOf(ResultMessage.Success.class, Await.value("the next", session.send("hi")));
    }
  }

  @Test
  void anInterruptReachesAToolBlockedOnItsToken() {
    var entered = new CountDownLatch(1);
    var observed = new CountDownLatch(1);
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(new ToolCall("c1", "wait", Map.of()))
            .withTextTurn("after")
            .build();
    var waiting =
        binding(
            "wait",
            (args, ctx) -> {
              ctx.cancellation().onCancel(observed::countDown);
              entered.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return ToolResult.failure("cancelled");
            });

    try (var session = AgentSession.open(options(model).withTools(List.of(waiting)).build())) {
      var run = session.send("go");
      Await.latch("the tool to run", entered);
      session.interrupt();

      assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the run", run));
      Await.latch("the tool to observe the interrupt", observed);
    }
  }

  @Test
  void anInterruptWhileAPreStopHookRunsEndsTheRunCancelled() {
    var model = ScriptedModel.newBuilder().withTextTurn("one").build();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    PreStopHook waiting =
        (response, ctx) -> {
          entered.countDown();
          Uninterruptible.await(release);
          return HookOutcome.cont();
        };

    try (var session = AgentSession.open(options(model).withHook(waiting).build())) {
      var run = session.send("go");
      Await.latch("the pre-stop hook to run", entered);
      session.interrupt();
      release.countDown();

      var cancelled = assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the run", run));
      assertEquals("interrupted", cancelled.reason());
    }
  }

  @Test
  void anInterruptWhileTheRunWaitsToDecideItsEndEndsTheRunCancelled() {
    var model = ScriptedModel.newBuilder().withTextTurn("one").build();
    var runThread = new AtomicReference<Thread>();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    PreStopHook waiting =
        (response, ctx) -> {
          entered.countDown();
          Uninterruptible.await(release);
          return HookOutcome.cont();
        };

    try (var session =
        AgentSession.open(
            options(model)
                .withHook(waiting)
                .withHook(
                    (OnStreamEventHook)
                        (event, ctx) -> {
                          if (event instanceof QueryEvent.RunStarted) {
                            runThread.set(Thread.currentThread());
                          }
                        })
                .build())) {
      var run = session.send("go");
      Await.latch("the pre-stop hook to run", entered);
      synchronized (((AgentSessionImpl) session).lifecycle.runs().lock) {
        release.countDown();
        Await.until(
            "the run's thread to wait to decide its end",
            () ->
                runThread.get().getState() == Thread.State.BLOCKED
                    && List.of(runThread.get().getStackTrace())
                        .toString()
                        .contains(SessionRuns.class.getName() + ".end("));
        session.interrupt();
      }

      var cancelled = assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the run", run));
      assertEquals("interrupted", cancelled.reason());
    }
  }

  // ── 9. a blocked first message ───────────────────────────────────────────

  @Test
  void aRunWhoseOnlyMessageIsBlockedEndsWithoutCallingTheModel() {
    var model = ScriptedModel.newBuilder().withTextTurn("one").build();

    try (var session = AgentSession.open(options(model).withHook(blocking()).build())) {
      Await.value("run 1", session.send("ok"));

      var failed =
          assertInstanceOf(
              ResultMessage.ErrorDuringExecution.class,
              Await.value("run 2", session.send("blocked")));

      assertEquals("NoUserMessage", failed.error().kind());
      assertEquals(1, model.calls().size());
    }
  }

  @Test
  void aFirstRunWithASystemPromptWhoseMessageIsBlockedCallsNoModel() {
    var model = ScriptedModel.newBuilder().withTextTurn("never").build();

    try (var session =
        AgentSession.open(options(model).withSystemPrompt("sys").withHook(blocking()).build())) {
      var failed =
          assertInstanceOf(
              ResultMessage.ErrorDuringExecution.class,
              Await.value("the run", session.send("blocked")));

      assertEquals(
          List.of(
              "NoUserMessage",
              "the run's first turn needs a user message: every message queued for it was"
                  + " blocked"),
          List.of(failed.error().kind(), failed.error().message()));
      assertEquals(List.of(), model.calls());
    }
  }

  @Test
  void aMessageSentWhileTheRunsOnlyQueuedMessageIsBlockedIsTheRunsUserMessage() {
    var model = ScriptedModel.newBuilder().withTextTurn("answered").build();
    var session = new AtomicReference<AgentSession>();
    OnUserMessageHook blockingWhileAnotherArrives =
        (message, ctx) -> {
          if (message.text().equals("blocked")) {
            session.get().send("allowed");
            return HookOutcome.block("not allowed");
          }
          return HookOutcome.cont();
        };

    try (var opened =
        AgentSession.open(options(model).withHook(blockingWhileAnotherArrives).build())) {
      session.set(opened);
      var run = Await.value("the run", opened.send("blocked"));

      assertInstanceOf(ResultMessage.Success.class, run);
      assertEquals(List.of(List.of(Message.user("allowed"))), model.calls());
    }
  }

  // ── 10. events ───────────────────────────────────────────────────────────

  @Test
  void oneReaderReceivesEveryRunBracketedAndEachEventNamesItsRun() {
    var model = ScriptedModel.newBuilder().withTextTurn("one").withTextTurn("two").build();
    var events = new EventCollector();
    var late = new EventCollector();

    try (var session = AgentSession.open(options(model).build())) {
      events.read(session.events());
      var first = Await.value("run 1", session.send("a")).runId();
      late.read(session.events());
      var second = Await.value("run 2", session.send("b")).runId();
      session.close();
      events.awaitDone();
      late.awaitDone();

      var types = events.events().stream().map(e -> e.getClass().getSimpleName()).toList();
      var runOfEach = events.events().stream().map(QueryEvent::runId).toList();
      var runOne =
          List.of(
              "RunStarted",
              "UserMessageReceived",
              "AssistantText",
              "ModelCallEnded",
              "TurnEnded",
              "RunEnded");
      var both = new ArrayList<>(runOne);
      both.addAll(runOne);
      assertEquals(both, types);
      assertEquals(
          Stream.concat(
                  Collections.nCopies(6, first).stream(), Collections.nCopies(6, second).stream())
              .toList(),
          runOfEach);
      assertEquals(
          Collections.nCopies(6, second), late.events().stream().map(QueryEvent::runId).toList());
    }
  }

  // ── 11. questions ────────────────────────────────────────────────────────

  @Test
  void aRunsEndCancelsItsQuestionAndTheNextRunWorks() {
    var ask =
        new ToolCall(
            "c-ask",
            AskUserQuestionTool.NAME,
            Map.of(
                "question",
                "Proceed?",
                "options",
                List.of(
                    Map.of("label", "Yes", "description", "go on"),
                    Map.of("label", "No", "description", "stop"))));
    var model = ScriptedModel.newBuilder().withToolCallsTurn(ask).withTextTurn("done").build();
    var asked = new CompletableFuture<QueryEvent.QuestionAsked>();
    var events =
        new EventCollector(
            event -> {
              if (event instanceof QueryEvent.QuestionAsked question) {
                asked.complete(question);
              }
            });

    try (var session = AgentSession.open(options(model).withAskUserQuestion().build())) {
      events.read(session.events());
      var run = session.send("go");
      var questionId = Await.value("the question", asked).request().questionId();
      session.interrupt();

      assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the run", run));
      assertEquals(List.of(), session.pendingQuestions());
      var response = new AskUserQuestionResponse(questionId, List.of("Yes"), "");
      assertThrows(IllegalArgumentException.class, () -> session.answer(questionId, response));
      assertInstanceOf(ResultMessage.Success.class, Await.value("the next", session.send("hi")));
    }
  }

  // ── fixtures ─────────────────────────────────────────────────────────────

  /**
   * A session over a scripted model whose first run ends one way, then whose second run answers
   * {@code done}; it records the turn index of every model turn.
   */
  static final class Fixture implements AutoCloseable {

    final List<Long> turns = new CopyOnWriteArrayList<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
    AgentSession session;

    ResultMessage maxTurns() {
      return firstRun(
          ScriptedModel.newBuilder().withToolCallsTurn(ECHO),
          SessionLimits.newBuilder().withMaxTurns(1).build());
    }

    ResultMessage budget() {
      return firstRun(
          ScriptedModel.newBuilder().withTextTurn("costly", Usage.of(100, 0)),
          SessionLimits.newBuilder().withMaxBudgetMicroUsd(1).build());
    }

    ResultMessage wallClock() {
      var entered = new CountDownLatch(1);
      var limits = SessionLimits.defaults();
      open(
          ScriptedModel.newBuilder().withStreamTurn(ModelStreams.stalled(entered::countDown)),
          limits);
      var run = session.send("go");
      Await.latch("the model stream to be read", entered);
      now.set(now.get().plus(limits.maxWallClock()).plusSeconds(1));
      ((AgentSessionImpl) session).lifecycle.expireWallClock();
      return Await.value("the first run", run);
    }

    ResultMessage interrupted() {
      var entered = new CountDownLatch(1);
      open(
          ScriptedModel.newBuilder().withStreamTurn(ModelStreams.stalled(entered::countDown)),
          SessionLimits.defaults());
      var run = session.send("go");
      Await.latch("the model stream to be read", entered);
      session.interrupt();
      return Await.value("the first run", run);
    }

    ResultMessage failed() {
      return firstRun(
          ScriptedModel.newBuilder()
              .withStreamTurn(ModelStreams.failing(new IllegalStateException("broken"))),
          SessionLimits.defaults());
    }

    ResultMessage refused() {
      return firstRun(ScriptedModel.newBuilder().withRefusalTurn("no"), SessionLimits.defaults());
    }

    private ResultMessage firstRun(ScriptedModel.Builder first, SessionLimits limits) {
      open(first, limits);
      return Await.value("the first run", session.send("go"));
    }

    private void open(ScriptedModel.Builder first, SessionLimits limits) {
      PreModelTurnHook recording =
          (history, ctx) -> {
            turns.add(ctx.turnIndex());
            return HookOutcome.cont();
          };
      session =
          AgentSession.open(
              options(first.withTextTurn("done").build())
                  .withTools(List.of(echo()))
                  .withLimits(limits)
                  .withClock(now::get)
                  .withCostCalculator((id, usage) -> CostEstimate.ofMicroUsd(usage.inputTokens()))
                  .withHook(recording)
                  .build());
    }

    @Override
    public void close() {
      session.close();
    }
  }

  private static SessionOptions.Builder options(ScriptedModel model) {
    return SessionOptions.newBuilder().withModel(model).withSessionId(SID);
  }

  private static ToolBinding echo() {
    return binding("echo", (args, ctx) -> ToolResult.success("echoed " + args.get("v")));
  }

  private static OnUserMessageHook blocking() {
    return (message, ctx) ->
        message.text().equals("blocked") ? HookOutcome.block("not allowed") : HookOutcome.cont();
  }

  private static Message notRun(ToolCall call) {
    return Message.tool(call.id(), call.name(), "[not run: the run ended before this call ran]");
  }

  private static List<String> receivedTexts(EventCollector events) {
    return events.eventsOf(QueryEvent.UserMessageReceived.class).stream()
        .map(event -> event.message().text())
        .toList();
  }

  private static QueryEvent.UserMessageReceived received(EventCollector events, String text) {
    return events.eventsOf(QueryEvent.UserMessageReceived.class).stream()
        .filter(event -> event.message().text().equals(text))
        .findFirst()
        .orElseThrow();
  }

  /** The texts of the messages the last run received. */
  private static List<String> lastRunMessages(EventCollector events) {
    var last = events.eventsOf(QueryEvent.RunStarted.class).getLast().runId();
    return events.eventsOf(QueryEvent.UserMessageReceived.class).stream()
        .filter(event -> event.runId().equals(last))
        .map(event -> event.message().text())
        .toList();
  }

  private static ModelChunk.MessageStop stop(String reason) {
    return new ModelChunk.MessageStop(reason, Usage.of(1, 1), Map.of());
  }

  /** A stream whose first read waits for {@code go}, then yields {@code chunks}. */
  private static CloseableIterator<ModelChunk> held(CountDownLatch go, ModelChunk... chunks) {
    var inner = ModelStreams.of(chunks);
    return new CloseableIterator<>() {
      @Override
      public boolean hasNext() {
        Await.latch("the release", go);
        return inner.hasNext();
      }

      @Override
      public ModelChunk next() {
        return inner.next();
      }

      @Override
      public void close() {
        inner.close();
      }
    };
  }
}
