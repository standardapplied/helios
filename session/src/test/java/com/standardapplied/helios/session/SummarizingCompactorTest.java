/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.BlockingModel;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.loop.SessionHookContext;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.testing.ScriptedModel;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.logging.LogRecord;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class SummarizingCompactorTest {

  private static final Duration SHORT_SUMMARY_TIMEOUT = Duration.ofMillis(50);

  private static final List<Message> CONVERSATION =
      List.of(
          Message.system("sys"),
          Message.user("go"),
          Message.assistant(
              "checking",
              List.of(new ToolCall("t1", "echo", Map.of())),
              Map.of("anthropic.thinkingBlocks", "[{\"text\":\"hm\",\"signature\":\"sig\"}]")),
          Message.tool("t1", "echo", "r1"),
          Message.assistant("done"));

  private final CountDownLatch released = new CountDownLatch(1);

  @AfterEach
  void releaseHangingFixtures() {
    released.countDown();
  }

  private static ScriptedModel summaryModel(String content) {
    return ScriptedModel.newBuilder().withId("summary").withTextTurn(content).build();
  }

  private static SessionState freshState() {
    return new SessionState("sess-1", new CancellationToken(), Clock.systemUTC());
  }

  /** The context the session hands its compactor at {@code state}'s current turn. */
  private static SessionHookContext contextOf(SessionState state) {
    return SessionHookContext.of(state, summaryModel("unused"));
  }

  private static SessionHookContext freshContext() {
    return contextOf(freshState());
  }

  private static List<Message> followedBy(Message... messages) {
    return Stream.concat(CONVERSATION.stream(), Stream.of(messages)).toList();
  }

  private static List<Message> onlyCall(ScriptedModel summarizer) {
    assertEquals(1, summarizer.calls().size(), "one summary request");
    return summarizer.calls().getFirst();
  }

  @Test
  void newBuilderRejectsNullSummaryModel() {
    assertThrows(NullPointerException.class, () -> SummarizingCompactor.newBuilder(null));
  }

  @Test
  void withSummaryPromptRejectsNull() {
    var b = SummarizingCompactor.newBuilder(summaryModel("x"));
    assertThrows(NullPointerException.class, () -> b.withSummaryPrompt(null));
  }

  @Test
  void withSummaryPromptRejectsBlank() {
    var b = SummarizingCompactor.newBuilder(summaryModel("x"));
    assertThrows(IllegalArgumentException.class, () -> b.withSummaryPrompt("   "));
  }

  @Test
  void withSummaryTimeoutRejectsNull() {
    var b = SummarizingCompactor.newBuilder(summaryModel("x"));
    assertThrows(NullPointerException.class, () -> b.withSummaryTimeout(null));
  }

  @Test
  void withSummaryTimeoutRejectsZero() {
    var b = SummarizingCompactor.newBuilder(summaryModel("x"));
    assertThrows(IllegalArgumentException.class, () -> b.withSummaryTimeout(Duration.ZERO));
  }

  @Test
  void withSummaryTimeoutRejectsNegative() {
    var b = SummarizingCompactor.newBuilder(summaryModel("x"));
    assertThrows(
        IllegalArgumentException.class, () -> b.withSummaryTimeout(Duration.ofSeconds(-1)));
  }

  @Test
  void compactRejectsNullHistory() {
    var compactor = SummarizingCompactor.newBuilder(summaryModel("x")).build();
    assertThrows(NullPointerException.class, () -> compactor.compact(null, freshContext()));
  }

  @Test
  void compactRejectsNullState() {
    var compactor = SummarizingCompactor.newBuilder(summaryModel("x")).build();
    assertThrows(NullPointerException.class, () -> compactor.compact(List.of(), null));
  }

  @Test
  void aPendingUserTurnFollowsTheSummaryOfEverythingBeforeIt() {
    var summarizer = summaryModel("the gist");
    var history = followedBy(Message.user("next"));

    var out = SummarizingCompactor.newBuilder(summarizer).build().compact(history, freshContext());

    assertEquals(
        List.of(
            Message.system("sys"),
            Message.user(SummarizingCompactor.SUMMARY_PREFIX + "the gist"),
            Message.user("next")),
        out.history());
    var request = onlyCall(summarizer);
    assertEquals(Role.SYSTEM, request.getFirst().role());
    assertEquals(
        Message.user(
            "[user] go\n[assistant] checking {tool_call echo}\n[tool] r1\n[assistant] done\n"),
        request.getLast());
    assertEquals(2, request.size());
  }

  @Test
  void aHistoryEndingInAnAssistantTurnBecomesTheSystemPromptAndTheSummary() {
    var out =
        SummarizingCompactor.newBuilder(summaryModel("the gist"))
            .build()
            .compact(CONVERSATION, freshContext());

    assertEquals(
        List.of(
            Message.system("sys"), Message.user(SummarizingCompactor.SUMMARY_PREFIX + "the gist")),
        out.history());
  }

  @Test
  void aUserMessageQueuedAfterAToolRoundStaysAfterTheSummary() {
    var history =
        List.of(
            Message.user("go"),
            Message.assistant(List.of(new ToolCall("t1", "echo", Map.of()))),
            Message.tool("t1", "echo", "r1"),
            Message.user("steer"));

    var out =
        SummarizingCompactor.newBuilder(summaryModel("the gist"))
            .build()
            .compact(history, freshContext());

    assertEquals(
        List.of(
            Message.user(SummarizingCompactor.SUMMARY_PREFIX + "the gist"), Message.user("steer")),
        out.history());
  }

  @Test
  void anOpenToolRoundLeavesTheSameHistoryWithoutASummaryCall() {
    var summarizer = summaryModel("never");
    var history =
        List.of(
            Message.system("sys"),
            Message.user("go"),
            Message.assistant(
                List.of(
                    new ToolCall("t1", "echo", Map.of()), new ToolCall("t2", "echo", Map.of()))),
            Message.tool("t1", "echo", "r1"));

    var out = SummarizingCompactor.newBuilder(summarizer).build().compact(history, freshContext());

    assertSame(history, out.history());
    assertEquals(List.of(), summarizer.calls());
  }

  @Test
  void aClosedToolRoundBeforeALaterTextTurnIsCompacted() {
    var history =
        List.of(
            Message.user("go"),
            Message.assistant(List.of(new ToolCall("t1", "echo", Map.of()))),
            Message.tool("t1", "echo", "r1"),
            Message.user("and?"),
            Message.assistant("done"));

    var out =
        SummarizingCompactor.newBuilder(summaryModel("the gist"))
            .build()
            .compact(history, freshContext());

    assertEquals(
        List.of(Message.user(SummarizingCompactor.SUMMARY_PREFIX + "the gist")), out.history());
  }

  @Test
  void fewerThanTwoMessagesToSummariseLeaveTheHistoryWithoutASummaryCall() {
    var summarizer = summaryModel("never");
    var onlyPending = List.of(Message.system("sys"), Message.user("a"), Message.user("b"));
    var oneMessage = List.of(Message.system("sys"), Message.assistant("a"), Message.user("b"));
    var compactor = SummarizingCompactor.newBuilder(summarizer).build();

    assertSame(onlyPending, compactor.compact(onlyPending, freshContext()).history());
    assertSame(oneMessage, compactor.compact(oneMessage, freshContext()).history());
    assertSame(List.<Message>of(), compactor.compact(List.of(), freshContext()).history());
    assertEquals(List.of(), summarizer.calls());
  }

  @Test
  void aMessageWithNullToolCallsAndContentRendersItsRoleAlone() {
    var summarizer = summaryModel("the gist");
    var bare = new Message(Role.ASSISTANT, null, null, null, null, Map.of(), List.of());

    SummarizingCompactor.newBuilder(summarizer)
        .build()
        .compact(List.of(Message.user("go"), bare), freshContext());

    assertEquals("[user] go\n[assistant] \n", onlyCall(summarizer).getLast().content());
  }

  @Test
  void customSummaryPromptIsTheSummaryRequestsSystemMessage() {
    var summarizer = summaryModel("done");
    var compactor =
        SummarizingCompactor.newBuilder(summarizer).withSummaryPrompt("strict prompt").build();

    compactor.compact(CONVERSATION, freshContext());

    assertEquals(Message.system("strict prompt"), onlyCall(summarizer).getFirst());
  }

  @Test
  void summaryUsageAndModelIdAreReportedOnTheResult() {
    var summarizer =
        ScriptedModel.newBuilder()
            .withId("summary")
            .withTextTurn("gist", Usage.of(150, 25))
            .build();

    var out =
        SummarizingCompactor.newBuilder(summarizer).build().compact(CONVERSATION, freshContext());

    assertEquals(Usage.of(150, 25), out.usage());
    assertEquals("summary", out.modelId());
  }

  @Test
  void aSummaryWithoutUsageReportsZeroUsage() {
    var summarizer =
        modelAnswering(() -> Response.newBuilder().withContent("gist").withUsage(null).build());

    var out =
        SummarizingCompactor.newBuilder(summarizer).build().compact(CONVERSATION, freshContext());

    assertEquals(Usage.of(0, 0), out.usage());
  }

  @Test
  void summarizerThrowingReturnsOriginalHistory() {
    var compactor =
        compactorSummarizingWith(
            () -> {
              throw new RuntimeException("boom");
            });

    var out = compactor.compact(CONVERSATION, freshContext());

    assertSame(CONVERSATION, out.history());
    assertEquals(Usage.of(0, 0), out.usage());
  }

  @Test
  void summarizerReturningBlankReturnsOriginalHistoryAndReportsTheCallsUsage() {
    var summarizer =
        ScriptedModel.newBuilder().withId("summary").withTextTurn(" ", Usage.of(40, 1)).build();

    var out =
        SummarizingCompactor.newBuilder(summarizer).build().compact(CONVERSATION, freshContext());

    assertSame(CONVERSATION, out.history());
    assertEquals(Usage.of(40, 1), out.usage());
    assertEquals("summary", out.modelId());
  }

  @Test
  void summarizerReturningNullContentReturnsOriginalHistory() {
    var compactor =
        SummarizingCompactor.newBuilder(modelAnswering(() -> Response.newBuilder().build()))
            .build();

    assertSame(CONVERSATION, compactor.compact(CONVERSATION, freshContext()).history());
  }

  @Test
  void summaryTimeoutLeavesHistoryUnchanged() {
    var interrupted = new CountDownLatch(1);
    var slow = blockingUntilInterrupted(interrupted);
    var compactor =
        SummarizingCompactor.newBuilder(slow).withSummaryTimeout(SHORT_SUMMARY_TIMEOUT).build();

    var out = compactor.compact(CONVERSATION, freshContext());

    assertSame(CONVERSATION, out.history());
    assertEquals(Usage.of(0, 0), out.usage());
    Await.latch("the timed-out summary call to be interrupted", interrupted);
  }

  @Test
  void anInterruptedCompactionLeavesTheHistoryKeepsTheInterruptAndInterruptsTheSummaryCall() {
    var interrupted = new CountDownLatch(1);
    var compactor = SummarizingCompactor.newBuilder(blockingUntilInterrupted(interrupted)).build();

    var compacted =
        CompletableFuture.supplyAsync(
            () -> {
              Thread.currentThread().interrupt();
              var out = compactor.compact(CONVERSATION, freshContext());
              return out.history() == CONVERSATION && Thread.currentThread().isInterrupted();
            },
            Thread.ofVirtual()::start);

    assertTrue(Await.value("the interrupted compaction", compacted));
    Await.latch("the summary call to be interrupted", interrupted);
  }

  @Test
  void anErrorTheSummarizerThrewLeavesTheCompactionAsItself() {
    var sentinel = new AssertionError("host failure");
    var compactor =
        compactorSummarizingWith(
            () -> {
              throw sentinel;
            });

    var thrown =
        assertThrows(AssertionError.class, () -> compactor.compact(CONVERSATION, freshContext()));

    assertSame(sentinel, thrown);
  }

  @Test
  void anErrorTheSummarizerJoinsFromAFailedFutureLeavesTheCompactionAsItself() {
    var sentinel = new AssertionError("host failure");
    var compactor = compactorSummarizingWith(() -> CompletableFuture.failedFuture(sentinel).join());
    var state = freshState();

    var thrown =
        assertThrows(AssertionError.class, () -> compactor.compact(CONVERSATION, contextOf(state)));

    assertSame(sentinel, thrown);
    assertSame(sentinel, state.ending().seal().orElseThrow());
  }

  @Test
  void aCancellationTheSummarizerThrewIsLoggedWithItsReasonAndLeavesTheHistoryUnchanged() {
    var cancelled = new CancellationException("summary transport cancelled");
    var compactor =
        compactorSummarizingWith(
            () -> {
              throw cancelled;
            });
    var logged = new ArrayList<LogRecord>();

    try (var ignored = LogCapture.of(SummarizingCompactor.class, logged)) {
      assertSame(CONVERSATION, compactor.compact(CONVERSATION, freshContext()).history());
    }

    var thrown = logged.getFirst().getThrown();
    assertInstanceOf(CancellationException.class, thrown);
    assertSame(cancelled, thrown.getCause());
  }

  @Test
  void aSummaryTimeoutPastTheNanosecondRangeStillCompacts() {
    var out =
        SummarizingCompactor.newBuilder(summaryModel("the gist"))
            .withSummaryTimeout(Duration.ofSeconds(Long.MAX_VALUE))
            .build()
            .compact(CONVERSATION, freshContext());

    assertEquals(
        List.of(
            Message.system("sys"), Message.user(SummarizingCompactor.SUMMARY_PREFIX + "the gist")),
        out.history());
  }

  @Test
  void aCheckedExceptionTheSummarizerThrewUndeclaredLeavesTheHistoryUnchangedAtOnce() {
    var compactor = compactorSummarizingWith(() -> throwUndeclared(new IOException("undeclared")));

    var compacted =
        CompletableFuture.supplyAsync(
            () -> compactor.compact(CONVERSATION, freshContext()), Thread.ofVirtual()::start);

    assertSame(CONVERSATION, Await.value("the compaction", compacted).history());
  }

  @Test
  void anErrorTheSummaryCallThrowsAfterItsTimeoutEndsTheSession() {
    var sentinel = new AssertionError("summary failure");
    var calling = new CompletableFuture<Thread>();
    var state = freshState();

    var out =
        timingOut(failingOnRelease(() -> fail(sentinel), calling))
            .compact(CONVERSATION, contextOf(state));
    released.countDown();
    Await.termination("the summary call to throw", Await.value("the summary call", calling));

    assertSame(CONVERSATION, out.history(), "timeout must yield no-op");
    assertTrue(state.cancellation().isCancelled(), "a host Error ends the session");
    assertSame(sentinel, state.ending().seal().orElseThrow());
  }

  @Test
  void anErrorTheSummaryCallJoinsFromAFailedFutureAfterItsTimeoutEndsTheSession() {
    var sentinel = new AssertionError("summary failure");
    var calling = new CompletableFuture<Thread>();
    var state = freshState();

    timingOut(failingOnRelease(() -> CompletableFuture.failedFuture(sentinel).join(), calling))
        .compact(CONVERSATION, contextOf(state));
    released.countDown();
    Await.termination("the summary call to throw", Await.value("the summary call", calling));

    assertSame(sentinel, state.ending().seal().orElseThrow());
  }

  @Test
  void anErrorTheSummarizerThrowsUnderAnotherContextIsOnlyRethrown() {
    var sentinel = new AssertionError("host failure");
    var compactor =
        compactorSummarizingWith(
            () -> {
              throw sentinel;
            });
    var state = freshState();
    var context =
        new DefaultHookContext(
            state.sessionId(),
            state.runId(),
            state.currentTurnIndex(),
            state.cancellation(),
            summaryModel("x"));

    var thrown = assertThrows(AssertionError.class, () -> compactor.compact(CONVERSATION, context));

    assertSame(sentinel, thrown);
    assertFalse(state.cancellation().isCancelled());
    assertEquals(Optional.empty(), state.ending().seal());
  }

  @Test
  void anErrorAnExecutionExceptionCarriesUnderAnotherContextIsRethrownAsItself() {
    var sentinel = new AssertionError("host failure");
    var compactor =
        compactorSummarizingWith(() -> throwUndeclared(new ExecutionException(sentinel)));
    var state = freshState();
    var context =
        new DefaultHookContext(
            state.sessionId(),
            state.runId(),
            state.currentTurnIndex(),
            state.cancellation(),
            summaryModel("x"));

    var thrown = assertThrows(AssertionError.class, () -> compactor.compact(CONVERSATION, context));

    assertSame(sentinel, thrown);
    assertEquals(Optional.empty(), state.ending().seal());
  }

  @Test
  void anErrorAnExecutionExceptionCarriesUnderTheSessionsContextIsRecordedAndRethrown() {
    var sentinel = new AssertionError("host failure");
    var compactor =
        compactorSummarizingWith(() -> throwUndeclared(new ExecutionException(sentinel)));
    var state = freshState();

    var thrown =
        assertThrows(AssertionError.class, () -> compactor.compact(CONVERSATION, contextOf(state)));

    assertSame(sentinel, thrown);
    assertSame(sentinel, state.ending().seal().orElseThrow());
  }

  @Test
  void disabledCompactorReturnsHistoryUnchanged() {
    var disabled = ContextCompactor.disabled();
    var out = disabled.compact(CONVERSATION, freshContext());
    assertSame(CONVERSATION, out.history());
    assertEquals(Usage.of(0, 0), out.usage());
  }

  @Test
  void disabledCompactorRejectsANullHistory() {
    var disabled = ContextCompactor.disabled();
    assertThrows(NullPointerException.class, () -> disabled.compact(null, freshContext()));
  }

  @Test
  void compactionResultNoOpHasZeroUsageAndBlankModelId() {
    var result = CompactionResult.noOp(CONVERSATION);
    assertSame(CONVERSATION, result.history());
    assertEquals(Usage.of(0, 0), result.usage());
    assertEquals("", result.modelId(), "noOp uses an empty modelId — no spend to attribute");
  }

  @Test
  void compactionResultRejectsNullArgs() {
    assertThrows(NullPointerException.class, () -> new CompactionResult(null, Usage.of(0, 0), ""));
    assertThrows(NullPointerException.class, () -> new CompactionResult(List.of(), null, ""));
    assertThrows(
        NullPointerException.class, () -> new CompactionResult(List.of(), Usage.of(0, 0), null));
  }

  @ParameterizedTest
  @CsvSource({"10, 5, 0, 0", "0, 0, 0, 900", "0, 0, 900, 0"})
  void compactionResultRejectsUsageWithoutModelId(
      int input, int output, int cacheWrites, int cacheReads) {
    var usage = Usage.of(input, output, cacheWrites, cacheReads);
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> new CompactionResult(List.of(), usage, " "));
    assertEquals(
        "modelId must be non-blank when usage reports any tokens; got " + usage, ex.getMessage());
  }

  @Test
  void aSummaryTimeoutIsLoggedAsATimeout() {
    var interrupted = new CountDownLatch(1);
    var logged = new ArrayList<LogRecord>();

    try (var ignored = LogCapture.of(SummarizingCompactor.class, logged)) {
      timingOut(blockingUntilInterrupted(interrupted)).compact(CONVERSATION, freshContext());
    }

    Await.latch("the timed-out summary call to be interrupted", interrupted);
    assertEquals(
        List.of(
            "Context compaction summary timed out after PT0.05S; leaving history unchanged."
                + " Session sess-1 turn 0"),
        logged.stream().map(LogRecord::getMessage).toList());
  }

  @Test
  void anInterruptedCompactionIsLoggedAsAnInterruptNotATimeout() {
    var interrupted = new CountDownLatch(1);
    var compactor = SummarizingCompactor.newBuilder(blockingUntilInterrupted(interrupted)).build();
    var logged = new ArrayList<LogRecord>();

    try (var ignored = LogCapture.of(SummarizingCompactor.class, logged)) {
      Thread.currentThread().interrupt();
      compactor.compact(CONVERSATION, freshContext());
      assertTrue(Thread.interrupted(), "the compaction keeps the interrupt");
    }

    Await.latch("the summary call to be interrupted", interrupted);
    assertInstanceOf(InterruptedException.class, logged.getFirst().getThrown().getCause());
  }

  @Test
  void aRuntimeExceptionTheSummarizerThrewIsLoggedAsItself() {
    var thrown = new IllegalStateException("summary transport failed");
    var compactor =
        compactorSummarizingWith(
            () -> {
              throw thrown;
            });
    var logged = new ArrayList<LogRecord>();

    try (var ignored = LogCapture.of(SummarizingCompactor.class, logged)) {
      assertSame(CONVERSATION, compactor.compact(CONVERSATION, freshContext()).history());
    }

    assertSame(thrown, logged.getFirst().getThrown());
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void throwUndeclared(Throwable t) throws E {
    throw (E) t;
  }

  private static void fail(AssertionError sentinel) {
    throw sentinel;
  }

  private interface Answer {
    Response<Void> respond();
  }

  /** A summary model whose every call returns what {@code answer} does. */
  private static Model modelAnswering(Answer answer) {
    return new BlockingModel() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return answer.respond();
      }

      @Override
      public String id() {
        return "summary";
      }

      @Override
      public String provider() {
        return "summary";
      }
    };
  }

  /** A summary model whose call blocks until interrupted, then counts {@code interrupted} down. */
  private static Model blockingUntilInterrupted(CountDownLatch interrupted) {
    return modelAnswering(
        () -> {
          try {
            new CountDownLatch(1).await();
          } catch (InterruptedException e) {
            interrupted.countDown();
          }
          return Response.newBuilder().withContent("never").build();
        });
  }

  private static SummarizingCompactor compactorSummarizingWith(Runnable summary) {
    return SummarizingCompactor.newBuilder(
            modelAnswering(
                () -> {
                  summary.run();
                  return Response.newBuilder().withContent("unreached").build();
                }))
        .withSummaryTimeout(Duration.ofMinutes(10))
        .build();
  }

  private static SummarizingCompactor timingOut(Model summaryModel) {
    return SummarizingCompactor.newBuilder(summaryModel)
        .withSummaryTimeout(SHORT_SUMMARY_TIMEOUT)
        .build();
  }

  /**
   * A summary model whose call reports its thread, blocks until {@link #released} ignoring
   * interrupts, then runs {@code failure}, which throws.
   */
  private Model failingOnRelease(Runnable failure, CompletableFuture<Thread> calling) {
    return modelAnswering(
        () -> {
          calling.complete(Thread.currentThread());
          Uninterruptible.await(released);
          failure.run();
          throw new IllegalStateException("the failure did not throw");
        });
  }
}
