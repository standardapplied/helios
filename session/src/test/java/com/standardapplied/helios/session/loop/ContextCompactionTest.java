/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.core.model.CancellationToken;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.session.CompactionResult;
import com.standardapplied.helios.session.ContextCompactor;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.SummarizingCompactor;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What the context step counts and how it judges a compaction: the limit a model's window yields,
 * the token count anchored on the provider's report, the size rule for a compactor's result, and a
 * cancelled summary call.
 */
final class ContextCompactionTest {

  private static final long TOKENS_PER_MESSAGE = 10L;
  private static final Usage FIRST_TURN = Usage.of(1_000, 7, 500, 4_000);
  private static final long FIRST_TURN_INPUT = 5_500L;

  /**
   * After the first turn the count is the report plus one message, and two messages are in reach.
   */
  private static final long LIMIT_OF_THE_LAST_REACHABLE_REPORT = 5_779L;

  private static final Duration NOT_REACHED = Duration.ofMinutes(10);

  private record Answer(String field) {}

  private final LoopFixture fixture =
      new LoopFixture("sess-context", Instant.parse("2026-10-30T09:00:00Z"));
  private final SteeringQueue queue = new SteeringQueue(8);
  private final SessionState state = fixture.state();
  private final List<List<Message>> counted = new ArrayList<>();
  private final TokenCounter recording =
      messages -> {
        counted.add(List.copyOf(messages));
        return TOKENS_PER_MESSAGE * messages.size();
      };
  private final CountDownLatch held = new CountDownLatch(1);

  @AfterEach
  void releaseTheSummaryCall() {
    held.countDown();
  }

  private AgentLoop loop(
      Model model, HookRegistry hooks, CostCalculator costs, ContextCompactor c) {
    var dispatch = fixture.dispatch(ToolRegistry.empty());
    var collaborators = fixture.collaborators(hooks, dispatch, queue);
    return fixture.loop(
        collaborators, fixture.runner(collaborators, model, costs, null), recording, c);
  }

  private AgentLoop loop(Model model, ContextCompactor compactor) {
    return loop(model, HookRegistry.empty(), CostCalculator.ZERO, compactor);
  }

  /** A loop whose compactor only matters to the check under test; its model is never called. */
  private AgentLoop checking(TokenCounter counter, ContextCompactor compactor) {
    var collaborators =
        fixture.collaborators(HookRegistry.empty(), fixture.dispatch(ToolRegistry.empty()), queue);
    return fixture.loop(collaborators, ScriptedModel.newBuilder().build(), counter, compactor);
  }

  private static SessionLimits maxContext(long tokens) {
    return SessionLimits.newBuilder().withMaxContextTokens(tokens).build();
  }

  /** Two turns, the second reporting {@code second}, a message steered in during the first. */
  private Model twoTurns(Usage second) {
    var script =
        ScriptedModel.newBuilder()
            .withTextTurn("one", FIRST_TURN)
            .withTextTurn("two", second)
            .build();
    return LoopModels.onEachCall(
        script,
        call -> {
          if (call == 1) {
            queue.offer(UserMessage.text("second"));
          }
        });
  }

  private void run(AgentLoop loop, long maxContextTokens) {
    queue.offer(UserMessage.text("first"));
    loop.run(state, maxContext(maxContextTokens), natural -> true);
  }

  private List<QueryEvent.ContextEdited> edits() {
    return fixture.events.stream()
        .filter(QueryEvent.ContextEdited.class::isInstance)
        .map(QueryEvent.ContextEdited.class::cast)
        .toList();
  }

  @ParameterizedTest
  @CsvSource({
    "8000, 0, 6000",
    "20000, 0, 15000",
    "20001, 0, 15001",
    "80000, 0, 60000",
    "200000, 0, 180000",
    "3, 0, 3",
    "1, 0, 1",
    "0, 0, 180000",
    "8000, 50000, 6000",
    "200000, 50000, 50000",
    "0, 50000, 50000"
  })
  void aWindowAndACapResolveToOneLimit(int window, long cap, long limit) {
    var model =
        LoopModels.withContextWindow(
            LoopModels.answering("ok", FinishReason.STOP, Usage.of(1, 1)), window);

    var compaction = loop(model, ContextCompactor.disabled()).compaction();

    assertEquals(limit, compaction.effectiveMaxContextTokens(maxContext(cap)));
  }

  @Test
  void aResultKeepingTheMessageCountWithShorterTextIsAccepted() {
    state.history().append(Message.user("a".repeat(400)));
    var shorter = List.of(Message.user("short"));

    checking(TokenCounter.charBased(), (history, context) -> CompactionResult.noOp(shorter))
        .compaction()
        .check(state, maxContext(100));

    assertEquals(shorter, state.history().snapshot());
    var edited = edits().getFirst();
    assertEquals(0, edited.removedBlocks());
    assertEquals(1, edits().size());
    assertTrue(edited.tokensAfter() < edited.tokensBefore());
  }

  @Test
  void fewerButLongerMessagesAreRejected() {
    state.history().append(Message.user("a".repeat(200)));
    state.history().append(Message.user("b".repeat(200)));
    var before = state.history().snapshot();
    var longer = List.of(Message.user("c".repeat(800)));

    checking(TokenCounter.charBased(), (history, context) -> CompactionResult.noOp(longer))
        .compaction()
        .check(state, maxContext(100));

    assertEquals(before, state.history().snapshot());
    assertEquals(List.of(), edits());
  }

  @Test
  void aResultOfEqualEstimateIsRejected() {
    state.history().append(Message.user("a"));
    state.history().append(Message.user("b"));
    var before = state.history().snapshot();
    var sameEstimate = List.of(Message.user("c"), Message.user("d"));

    checking(recording, (history, context) -> CompactionResult.noOp(sameEstimate))
        .compaction()
        .check(state, maxContext(10));

    assertEquals(before, state.history().snapshot());
    assertEquals(List.of(), edits());
  }

  @Test
  void theSameInstanceChangesNothingAndCountsNothingFurther() {
    state.history().append(Message.user("a"));
    var before = state.history().snapshot();

    checking(recording, ContextCompactor.disabled()).compaction().check(state, maxContext(10));

    assertEquals(before, state.history().snapshot());
    assertEquals(List.of(), edits());
    assertEquals(List.of(before, before), counted);
  }

  @Test
  void aNullResultChangesNothingAndCountsNothingFurther() {
    state.history().append(Message.user("a"));
    var before = state.history().snapshot();

    checking(recording, (history, context) -> null).compaction().check(state, maxContext(10));

    assertEquals(before, state.history().snapshot());
    assertEquals(List.of(), edits());
    assertEquals(List.of(before, before), counted);
  }

  @Test
  void anAcceptedResultWithMoreMessagesReportsNoneRemoved() {
    state.history().append(Message.user("a".repeat(800)));
    var split = List.of(Message.user("short"), Message.user("short"));

    checking(TokenCounter.charBased(), (history, context) -> CompactionResult.noOp(split))
        .compaction()
        .check(state, maxContext(100));

    assertEquals(split, state.history().snapshot());
    assertEquals(0, edits().getFirst().removedBlocks());
  }

  @Test
  void aReportedTurnAnchorsTheCountOnItsInputAndEstimatesOnlyWhatFollowed() {
    run(loop(twoTurns(Usage.of(6_000, 7)), ContextCompactor.disabled()), 6_400L);

    var history = state.history().snapshot();
    assertEquals(
        List.of(
            history.subList(0, 1),
            history.subList(1, 2),
            history.subList(1, 3),
            history.subList(3, 4)),
        counted);
    var warning =
        fixture.events.stream()
            .filter(QueryEvent.ContextWarning.class::isInstance)
            .map(QueryEvent.ContextWarning.class::cast)
            .toList()
            .getFirst();
    assertEquals(1L, warning.turnIndex());
    assertEquals((FIRST_TURN_INPUT + TOKENS_PER_MESSAGE) / 6_400.0, warning.usagePct(), 1e-9);
  }

  @Test
  void beforeAnyTurnTheWholeHistoryIsCounted() {
    state.history().append(Message.system("sys"));
    state.history().append(Message.user("loaded"));

    run(loop(twoTurns(Usage.of(6_000, 7)), ContextCompactor.disabled()), 1_000_000L);

    assertEquals(state.history().snapshot().subList(0, 3), counted.getFirst());
  }

  @Test
  void anAcceptedCompactionClearsTheAnchorAndItsSummaryCallNeverSetsOne() {
    ContextCompactor toLastMessage =
        (history, context) ->
            new CompactionResult(List.of(history.getLast()), Usage.of(9_000, 1), "summary");

    run(loop(twoTurns(Usage.of(50, 7)), toLastMessage), LIMIT_OF_THE_LAST_REACHABLE_REPORT);

    var history = state.history().snapshot();
    assertEquals(
        List.of(history.subList(0, 1), history.subList(0, 2), history.subList(2, 3)),
        counted.subList(3, counted.size()));
    var edited = edits().getFirst();
    assertEquals(1, edited.removedBlocks());
    assertEquals(2 * TOKENS_PER_MESSAGE, edited.tokensBefore());
    assertEquals(TOKENS_PER_MESSAGE, edited.tokensAfter());
  }

  @Test
  void aSummaryCallsUsageOnARejectedResultLeavesTheModelsAnchor() {
    var script = ScriptedModel.newBuilder().withTextTurn("one", FIRST_TURN).build();
    var compactions = new AtomicInteger();
    ContextCompactor reporting =
        (history, context) -> {
          compactions.incrementAndGet();
          return new CompactionResult(history, Usage.of(9_000, 1), "summary");
        };

    run(loop(script, reporting), LIMIT_OF_THE_LAST_REACHABLE_REPORT);

    assertEquals(1, compactions.get());
    assertEquals(FIRST_TURN_INPUT + TOKENS_PER_MESSAGE, state.history().tokens(recording));
  }

  @Test
  void noCompactorRunsWhileWhatItCannotSeeReachesTheWatermarkByItself() {
    var script = ScriptedModel.newBuilder().withTextTurn("one", FIRST_TURN).build();
    var compactions = new AtomicInteger();
    ContextCompactor counting =
        (history, context) -> {
          compactions.incrementAndGet();
          return CompactionResult.noOp(history);
        };

    run(loop(script, counting), LIMIT_OF_THE_LAST_REACHABLE_REPORT - 1);

    assertEquals(0, compactions.get());
    assertEquals(
        List.of("first", "one"),
        state.history().snapshot().stream().map(Message::content).toList());
  }

  @Test
  void aMutateHistoryHookClearsTheAnchor() {
    var rewritten = List.of(Message.user("rewritten"));
    PreModelTurnHook rewriteSecondTurn =
        (history, context) ->
            context.turnIndex() == 2 ? HookOutcome.mutateHistory(rewritten) : HookOutcome.cont();
    CostCalculator unpricedSecondTurn =
        (modelId, usage) -> {
          if (usage.inputTokens() == 6_000) {
            throw new IllegalStateException("the second turn ends the run before it anchors");
          }
          return CostEstimate.zero();
        };
    var loop =
        loop(
            twoTurns(Usage.of(6_000, 7)),
            new HookRegistry(List.of(rewriteSecondTurn), new SecretRegistry()),
            unpricedSecondTurn,
            ContextCompactor.disabled());

    run(loop, 1_000_000L);

    assertEquals(rewritten, state.history().snapshot());
    assertEquals(TOKENS_PER_MESSAGE, state.history().tokens(recording));
    assertEquals(rewritten, counted.getLast());
  }

  @Test
  void aTurnAfterAMutateHistoryHookAnchorsOnTheRewrittenHistory() {
    var rewritten = List.of(Message.user("rewritten"));
    PreModelTurnHook rewriteSecondTurn =
        (history, context) ->
            context.turnIndex() == 2 ? HookOutcome.mutateHistory(rewritten) : HookOutcome.cont();
    var loop =
        loop(
            twoTurns(Usage.of(6_000, 7)),
            new HookRegistry(List.of(rewriteSecondTurn), new SecretRegistry()),
            CostCalculator.ZERO,
            ContextCompactor.disabled());

    run(loop, 1_000_000L);

    var history = state.history().snapshot();
    assertEquals(2, history.size());
    assertEquals(history.subList(1, 2), counted.getLast());
    assertEquals(6_000L + TOKENS_PER_MESSAGE, state.history().tokens(recording));
  }

  @Test
  void aTurnThatSelfCorrectsItsSchemaStillAnchors() {
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                ModelStreams.failing(
                    new StructuredOutputParseException(List.of("field is missing"), "{}"),
                    new ModelChunk.MessageStop("STOP", FIRST_TURN, Map.of())))
            .withTextTurn("{\"field\":\"recovered\"}", Usage.of(6_000, 7))
            .build();
    var collaborators =
        fixture.collaborators(HookRegistry.empty(), fixture.dispatch(ToolRegistry.empty()), queue);
    var runner =
        fixture.runner(collaborators, model, CostCalculator.ZERO, OutputSchema.of(Answer.class));

    run(fixture.loop(collaborators, runner, recording, ContextCompactor.disabled()), 1_000_000L);

    assertEquals(state.history().snapshot().subList(1, 2), counted.get(1));
  }

  @Test
  void aReportOfOneInputTokenAnchors() {
    state.history().append(Message.user("a"));

    state.history().anchor(1, Usage.of(1, 0));

    assertEquals(1L, state.history().tokens(recording));
  }

  @Test
  void aTurnReportingNoInputClearsTheAnchor() {
    run(loop(twoTurns(Usage.of(0, 7)), ContextCompactor.disabled()), 1_000_000L);

    assertEquals(state.history().snapshot(), counted.getLast());
  }

  @Test
  void cancellingTheSessionEndsASummaryCallThatIgnoresItsInterrupt() {
    var token = new CancellationToken();
    var cancellable = fixture.state(token);
    cancellable.history().append(Message.user("earlier"));
    cancellable.history().append(Message.assistant("reply"));
    queue.offer(UserMessage.text("go"));
    var entered = new CountDownLatch(1);
    var summaryModel =
        LoopModels.onEachCall(
            ScriptedModel.newBuilder().withTextTurn("never used").build(),
            call -> {
              entered.countDown();
              Uninterruptible.await(held);
            });
    var summarizing =
        SummarizingCompactor.newBuilder(summaryModel).withSummaryTimeout(NOT_REACHED).build();
    var callbacks = new ArrayList<Integer>();
    ContextCompactor observed =
        (history, context) -> {
          callbacks.add(token.activeCallbackCountForTests());
          var result = summarizing.compact(history, context);
          callbacks.add(token.activeCallbackCountForTests());
          return result;
        };
    var model = ScriptedModel.newBuilder().withTextTurn("never used").build();
    var loop = loop(model, observed);

    var run =
        CompletableFuture.supplyAsync(
            () -> loop.run(cancellable, maxContext(10), natural -> true),
            Thread.ofVirtual()::start);
    Await.latch("the summary call to start", entered);
    token.cancel("stop");

    var terminal = Await.value("the cancelled run", run);
    var cancelled = assertInstanceOf(ResultMessage.Cancelled.class, terminal);
    assertEquals("stop", cancelled.reason());
    assertEquals(3, cancellable.history().size());
    assertEquals("earlier", cancellable.history().snapshot().getFirst().content());
    assertEquals(callbacks.get(0), callbacks.get(1));
    assertEquals(List.of(), edits());
    assertEquals(List.of(), model.calls());
  }
}
