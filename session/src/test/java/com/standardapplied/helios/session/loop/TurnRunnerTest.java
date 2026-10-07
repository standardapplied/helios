/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.StopReason;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.HookRegistry;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class TurnRunnerTest {

  private static final String SID = "sess-1";

  private final LoopFixture fixture = new LoopFixture(SID, Instant.parse("2026-05-14T19:00:00Z"));
  private final List<QueryEvent> events = fixture.events;
  private final HookRegistry hooks = HookRegistry.empty();
  private final SteeringQueue queue = new SteeringQueue(8);
  private final ToolDispatch dispatch = fixture.dispatch(ToolRegistry.empty());

  @AfterEach
  void closeFixture() {
    fixture.close();
  }

  private SessionState freshState() {
    var s = fixture.state();
    s.history().append(Message.user("hello"));
    s.beginTurn();
    return s;
  }

  private static Model textModel(String content, FinishReason finishReason, Usage usage) {
    return LoopModels.answering(content, finishReason, usage);
  }

  private static Model streaming(Flow.Publisher<ModelChunk> stream) {
    return ScriptedModel.newBuilder().withStreamTurn(stream).build();
  }

  private TurnRunner runner(Model model) {
    return fixture.runner(fixture.collaborators(hooks, dispatch, queue), model);
  }

  @Test
  void nullCollaboratorsRejected() {
    var model = textModel("x", FinishReason.STOP, Usage.of(1, 1));
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new TurnRunner(null, model, CostCalculator.ZERO, null, fixture.scheduler()));
    assertEquals("collaborators must not be null", ex.getMessage());
  }

  @Test
  void nullModelRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new TurnRunner(
                    fixture.collaborators(hooks, dispatch, queue),
                    null,
                    CostCalculator.ZERO,
                    null,
                    fixture.scheduler()));
    assertEquals("model must not be null", ex.getMessage());
  }

  @Test
  void nullCostCalculatorRejected() {
    var model = textModel("x", FinishReason.STOP, Usage.of(1, 1));
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new TurnRunner(
                    fixture.collaborators(hooks, dispatch, queue),
                    model,
                    null,
                    null,
                    fixture.scheduler()));
    assertEquals("costCalculator must not be null", ex.getMessage());
  }

  @Test
  void nullSchedulerRejected() {
    var model = textModel("x", FinishReason.STOP, Usage.of(1, 1));
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new TurnRunner(
                    fixture.collaborators(hooks, dispatch, queue),
                    model,
                    CostCalculator.ZERO,
                    null,
                    null));
    assertEquals("scheduler must not be null", ex.getMessage());
  }

  @Test
  void runTurnRejectsNullState() {
    var r = runner(textModel("x", FinishReason.STOP, Usage.of(1, 1)));
    var ex =
        assertThrows(NullPointerException.class, () -> r.runTurn(null, SessionLimits.defaults()));
    assertEquals("state must not be null", ex.getMessage());
  }

  @Test
  void runTurnRejectsNullLimits() {
    var r = runner(textModel("x", FinishReason.STOP, Usage.of(1, 1)));
    var ex = assertThrows(NullPointerException.class, () -> r.runTurn(freshState(), null));
    assertEquals("limits must not be null", ex.getMessage());
  }

  @Test
  void successfulTextTurnEmitsAssistantTextThenTurnEnded() {
    var state = freshState();
    var outcome =
        runner(textModel("hello world", FinishReason.STOP, Usage.of(5, 3)))
            .runTurn(state, SessionLimits.defaults());

    assertEquals(FinishReason.STOP, outcome.finishReason());
    assertEquals("hello world", outcome.assistantContent());
    assertEquals(Usage.of(5, 3), outcome.usage());

    assertEquals(2, events.size());
    var text = assertInstanceOf(QueryEvent.AssistantText.class, events.get(0));
    assertEquals("hello world", text.text());
    assertEquals(state.sessionId(), text.sessionId());
    assertEquals(state.currentTurnIndex(), text.turnIndex());

    var ended = assertInstanceOf(QueryEvent.TurnEnded.class, events.get(1));
    assertEquals(StopReason.END_TURN, ended.reason());
  }

  @Test
  void successfulTurnAppendsAssistantMessageToHistory() {
    var state = freshState();
    runner(textModel("answer", FinishReason.STOP, Usage.of(2, 1)))
        .runTurn(state, SessionLimits.defaults());

    var history = state.history().snapshot();
    assertEquals(2, history.size());
    assertEquals("hello", history.get(0).content());
    assertEquals("answer", history.get(1).content());
    assertEquals(com.standardapplied.helios.core.model.Role.ASSISTANT, history.get(1).role());
  }

  @Test
  void emptyContentDoesNotAppendAssistantMessage() {
    var state = freshState();
    runner(textModel("", FinishReason.STOP, Usage.of(2, 0)))
        .runTurn(state, SessionLimits.defaults());

    var history = state.history().snapshot();
    assertEquals(1, history.size(), "no assistant message appended for empty content");
  }

  @Test
  void usageAccumulatesToState() {
    var state = freshState();
    runner(textModel("answer", FinishReason.STOP, Usage.of(5, 3)))
        .runTurn(state, SessionLimits.defaults());
    assertEquals(5, state.totals().usage().inputTokens());
    assertEquals(3, state.totals().usage().outputTokens());
  }

  @Test
  void toolCallsFinishReasonProducesToolUseStopReason() {
    var state = freshState();
    var outcome =
        runner(textModel("calling tool", FinishReason.TOOL_CALLS, Usage.of(3, 2)))
            .runTurn(state, SessionLimits.defaults());
    assertEquals(FinishReason.TOOL_CALLS, outcome.finishReason());
    var ended = assertInstanceOf(QueryEvent.TurnEnded.class, events.get(events.size() - 1));
    assertEquals(StopReason.TOOL_USE, ended.reason());
  }

  @Test
  void lengthFinishReasonMapsToMaxTokens() {
    runner(textModel("partial", FinishReason.LENGTH, Usage.of(3, 100)))
        .runTurn(freshState(), SessionLimits.defaults());
    var ended = assertInstanceOf(QueryEvent.TurnEnded.class, events.get(events.size() - 1));
    assertEquals(StopReason.MAX_TOKENS, ended.reason());
  }

  @Test
  void contentFilterFinishReasonMapsToRefusal() {
    runner(textModel("I cannot help", FinishReason.CONTENT_FILTER, Usage.of(3, 2)))
        .runTurn(freshState(), SessionLimits.defaults());
    var ended = assertInstanceOf(QueryEvent.TurnEnded.class, events.get(events.size() - 1));
    assertEquals(StopReason.REFUSAL, ended.reason());
  }

  /** A model whose one turn streams {@code chunks}, then completes. */
  private static Model syntheticStreamingModel(List<ModelChunk> chunks) {
    return streaming(ModelStreams.of(chunks.toArray(ModelChunk[]::new)));
  }

  @Test
  void thinkingChunksProduceAssistantThinkingEvents() {
    var model =
        syntheticStreamingModel(
            List.of(
                new ModelChunk.ThinkingDelta("planning..."),
                new ModelChunk.TextDelta("answer"),
                new ModelChunk.MessageStop("STOP", Usage.of(4, 2), Map.of(), List.of())));
    var outcome = runner(model).runTurn(freshState(), SessionLimits.defaults());
    assertEquals("answer", outcome.assistantContent());
    var thinking =
        events.stream()
            .filter(e -> e instanceof QueryEvent.AssistantThinking)
            .map(e -> (QueryEvent.AssistantThinking) e)
            .findFirst()
            .orElseThrow();
    assertEquals("planning...", thinking.text());
    assertEquals("", thinking.signature());
  }

  @Test
  void refusedTurnNeverRunsToolCallsItEmittedBeforeTheRefusal() {
    var call = new ToolCall("c", "delete_everything", Map.of());
    var model =
        syntheticStreamingModel(
            List.of(
                new ModelChunk.ToolUseStop(call),
                new ModelChunk.MessageStop(
                    "REFUSAL",
                    Usage.of(9, 3),
                    Map.of(Response.REFUSAL_CATEGORY_KEY, "cyber"),
                    List.of())));
    var state = freshState();

    var outcome = runner(model).runTurn(state, SessionLimits.defaults());

    assertEquals(FinishReason.REFUSAL, outcome.finishReason());
    assertEquals("cyber", outcome.metadata().get(Response.REFUSAL_CATEGORY_KEY));
    assertTrue(
        events.stream()
            .noneMatch(e -> e instanceof QueryEvent.ToolUse || e instanceof QueryEvent.ToolResult),
        "a refused turn's partial output is discarded, tool calls included");
    assertTrue(state.history().snapshot().stream().noneMatch(Message::hasToolCalls));
  }

  @Test
  void usageDeltaAndToolUseChunksAreIgnored() {
    var call = new ToolCall("c", "ignored", Map.of());
    var model =
        syntheticStreamingModel(
            List.of(
                new ModelChunk.UsageDelta(Usage.of(1, 0)),
                new ModelChunk.ToolUseStart("c", "ignored"),
                new ModelChunk.ToolUseDelta("c", "{}"),
                new ModelChunk.ToolUseStop(call),
                new ModelChunk.TextDelta("done"),
                new ModelChunk.MessageStop("STOP", Usage.of(7, 2), Map.of(), List.of())));
    var outcome = runner(model).runTurn(freshState(), SessionLimits.defaults());
    assertEquals("done", outcome.assistantContent());
    assertEquals(Usage.of(7, 2), outcome.usage(), "MessageStop usage wins; UsageDelta ignored");
    assertEquals(
        1,
        events.stream().filter(e -> e instanceof QueryEvent.AssistantText).count(),
        "ignored chunks emit no events");
  }

  @Test
  void onErrorPublisherProducesErrorOutcomeAndNoAssistantMessage() {
    var model = streaming(ModelStreams.failing(new RuntimeException("upstream boom")));
    var state = freshState();
    var outcome = runner(model).runTurn(state, SessionLimits.defaults());
    assertEquals(FinishReason.ERROR, outcome.finishReason());
    assertEquals("upstream boom", outcome.assistantContent());
    assertEquals(
        1, state.history().snapshot().size(), "error turn does not append assistant message");
    var ended = assertInstanceOf(QueryEvent.TurnEnded.class, events.get(events.size() - 1));
    assertEquals(StopReason.ERROR, ended.reason());
  }

  @Test
  void onErrorWithNullMessageFallsBackToExceptionClassName() {
    var model = streaming(ModelStreams.failing(new RuntimeException()));
    var outcome = runner(model).runTurn(freshState(), SessionLimits.defaults());
    assertEquals("RuntimeException", outcome.assistantContent());
  }

  @Test
  void unknownStopReasonStringFallsBackToStop() {
    var model =
        syntheticStreamingModel(
            List.of(
                new ModelChunk.TextDelta("hi"),
                new ModelChunk.MessageStop("unknown_reason", Usage.of(1, 1), Map.of(), List.of())));
    var outcome = runner(model).runTurn(freshState(), SessionLimits.defaults());
    assertEquals(FinishReason.STOP, outcome.finishReason());
  }

  @Test
  void hooksFireForLifecyclePhases() {
    var runner = runner(textModel("ok", FinishReason.STOP, Usage.of(1, 1)));
    runner.runTurn(freshState(), SessionLimits.defaults());
    // With an empty registry, lifecycle hook calls return Continue silently; the test verifies the
    // turn produced TurnEnded.
    assertTrue(events.stream().anyMatch(e -> e instanceof QueryEvent.TurnEnded));
  }

  /**
   * A model whose stream never delivers a chunk or a terminal signal. {@code onRequest} runs on the
   * runner's thread once it has requested the stream, just before it starts waiting on it.
   */
  private static Model stalledModel(Runnable onRequest) {
    return streaming(ModelStreams.stalled(onRequest));
  }

  /**
   * Runs one turn against {@link #stalledModel}. The stream-idle watchdog is set beyond the hang
   * guard, so only an interrupt can end the turn.
   */
  private TurnOutcome runStalledTurn(Runnable onRequest) {
    var limits = SessionLimits.newBuilder().withStreamIdleTimeout(Duration.ofMinutes(10)).build();
    return runner(stalledModel(onRequest)).runTurn(freshState(), limits);
  }

  private void assertTurnEndedByInterrupt(TurnOutcome outcome) {
    assertEquals(FinishReason.ERROR, outcome.finishReason());
    assertInstanceOf(InterruptedException.class, outcome.streamError());
    var ended = assertInstanceOf(QueryEvent.TurnEnded.class, events.get(events.size() - 1));
    assertEquals(StopReason.ERROR, ended.reason());
  }

  @Test
  void interruptedAwaitPropagatesAsErrorOutcome() {
    var requested = new CountDownLatch(1);
    var turn = new FutureTask<>(() -> runStalledTurn(requested::countDown));
    var runnerThread = Thread.ofVirtual().start(turn);
    Await.latch("the runner to request the model stream", requested);
    Await.until(
        "the runner to block on the stalled stream",
        () -> runnerThread.getState() == Thread.State.WAITING);

    runnerThread.interrupt();

    assertTurnEndedByInterrupt(Await.value("the interrupted turn to return", turn));
  }

  @Test
  void interruptPendingBeforeAwaitPropagatesAsErrorOutcome() {
    var turn = new FutureTask<>(() -> runStalledTurn(() -> Thread.currentThread().interrupt()));
    Thread.ofVirtual().start(turn);

    assertTurnEndedByInterrupt(Await.value("the interrupted turn to return", turn));
  }

  @Test
  void elapsedDurationReadable() {
    // Lightweight smoke that state.elapsed() works after a turn completes.
    var state = freshState();
    runner(textModel("ok", FinishReason.STOP, Usage.of(1, 1)))
        .runTurn(state, SessionLimits.defaults());
    assertEquals(Duration.ZERO, state.elapsed());
  }

  // ── outputSchema dispatch: schema is transmitted to the model when configured ──

  /**
   * Sample record used to construct an {@link com.standardapplied.helios.core.schema.OutputSchema}
   * for tests exercising the typed dispatch branch in {@link TurnRunner}.
   */
  public record Sample(String field) {}

  /** A model whose one turn streams {@code content} as a finished text answer. */
  private static ScriptedModel answeringStream(String content) {
    return ScriptedModel.newBuilder()
        .withStreamTurn(
            ModelStreams.of(
                new ModelChunk.TextDelta(content),
                new ModelChunk.MessageStop(FinishReason.STOP.name(), Usage.of(1, 1), Map.of())))
        .build();
  }

  @Test
  void dispatchUsesUntypedChatStreamWhenOutputSchemaIsNull() {
    var model = answeringStream("untyped");
    var outcome = runner(model).runTurn(freshState(), SessionLimits.defaults());
    assertEquals(FinishReason.STOP, outcome.finishReason());
    assertEquals("untyped", outcome.assistantContent());
    var schemas = model.outputSchemas();
    assertTrue(
        schemas.contains(Optional.empty()), "no outputSchema configured: must use untyped path");
    assertEquals(
        false, schemas.stream().anyMatch(Optional::isPresent), "typed dispatch must not fire");
  }

  @Test
  void dispatchUsesTypedChatStreamWhenOutputSchemaIsConfigured() {
    var schema = OutputSchema.of(Sample.class);
    var model = answeringStream("typed-with-schema");
    var runner =
        fixture.runner(
            fixture.collaborators(hooks, dispatch, queue), model, CostCalculator.ZERO, schema);
    var outcome = runner.runTurn(freshState(), SessionLimits.defaults());
    assertEquals(FinishReason.STOP, outcome.finishReason());
    assertEquals("typed-with-schema", outcome.assistantContent());
    var schemas = model.outputSchemas();
    assertTrue(
        schemas.stream().anyMatch(Optional::isPresent),
        "outputSchema set: must use typed-with-schema path");
    assertEquals(
        false,
        schemas.contains(Optional.empty()),
        "untyped dispatch must not fire — that's the bug the wiring fixes");
    assertEquals(
        Optional.of(schema),
        schemas.getFirst(),
        "schema delivered to the provider must be the configured one");
  }

  /**
   * Defensive: when {@link StructuredOutputParseException} fires <i>and</i> the steering queue is
   * full so the correction message can't be enqueued, the runner must let the underlying parse
   * error surface (returns {@link FinishReason#ERROR}) rather than silently swallowing it.
   */
  @Test
  void parseFailureWithFullSteeringQueueFallsThroughToErrorOutcome() {
    var schema = OutputSchema.of(Sample.class);
    var saturatedQueue = new SteeringQueue(1);
    saturatedQueue.offer(UserMessage.text("pre-existing"));
    var model =
        streaming(
            ModelStreams.failing(
                new StructuredOutputParseException(
                    List.of("field is required"), "{\"wrong\":\"shape\"}")));
    var runner =
        fixture.runner(
            fixture.collaborators(hooks, dispatch, saturatedQueue),
            model,
            CostCalculator.ZERO,
            schema);
    var outcome = runner.runTurn(freshState(), SessionLimits.defaults());
    assertEquals(
        FinishReason.ERROR,
        outcome.finishReason(),
        "queue-full fallthrough: parse error must surface, not be silently swallowed");
  }
}
