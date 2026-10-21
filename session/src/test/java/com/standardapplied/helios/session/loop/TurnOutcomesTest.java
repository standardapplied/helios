/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.ContextCompactor;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.HookRegistry;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Only a turn that ended in {@code TOOL_CALLS} or {@code STOP} runs the tool calls it emitted; a
 * failed, truncated, cancelled or unterminated turn discards them and ends in its own terminal. A
 * session with {@code maxTurns = N} admits N model turns. Each test runs the loop to its terminal
 * {@link QueryEvent.LoopEnded} before it counts the recording tool's invocations.
 */
final class TurnOutcomesTest {

  private static final String SID = "sess-outcomes";
  private static final ToolCall CALL = new ToolCall("c-1", "record", Map.of());

  private final LoopFixture fixture = new LoopFixture(SID, Instant.parse("2026-10-21T08:00:00Z"));
  private final SteeringQueue queue = new SteeringQueue(8);
  private final AtomicInteger invocations = new AtomicInteger();
  private final SessionState state = fixture.state();

  @AfterEach
  void closeFixture() {
    fixture.close();
  }

  @Test
  void aStreamErrorAfterAToolCallRunsNoToolAndEndsWithThatError() {
    var failure = new IllegalStateException("connection reset");

    var terminal = run(failingAfterCall(failure), SessionLimits.defaults());

    var error = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, terminal);
    assertEquals(IllegalStateException.class.getName(), error.error().kind());
    assertEquals("connection reset", error.error().message());
    assertToolNeverRan();
  }

  @Test
  void aTruncatedTurnRunsNoToolAndEndsWithMaxTokens() {
    var truncated =
        ModelStreams.of(
            new ModelChunk.ToolUseStop(CALL),
            new ModelChunk.MessageStop("LENGTH", Usage.of(3, 2), Map.of(), List.of()));

    var terminal = run(streaming(truncated), SessionLimits.defaults());

    var error = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, terminal);
    assertEquals("max-tokens", error.error().kind());
    assertToolNeverRan();
  }

  @Test
  void aStreamWithoutItsMessageStopRunsNoToolAndEndsInError() {
    var unterminated = ModelStreams.of(new ModelChunk.ToolUseStop(CALL));

    var terminal = run(streaming(unterminated), SessionLimits.defaults());

    var error = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, terminal);
    assertEquals("model stream completed without its MessageStop", error.error().message());
    assertToolNeverRan();
  }

  @Test
  void aTurnCancelledWhileItsStreamIsOpenRunsNoToolAndEndsCancelled() {
    var callDelivered = new CountDownLatch(1);
    var loop = loop(streaming(openAfterCall(callDelivered)));
    queue.offer(UserMessage.text("go"));
    var run = new FutureTask<>(() -> loop.run(state, SessionLimits.defaults()));
    Thread.ofVirtual().start(run);

    Await.latch("the stream to deliver its tool call", callDelivered);
    state.cancellation().cancel("test cancel");

    var cancelled = assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the run", run));
    assertEquals("test cancel", cancelled.reason());
    assertToolNeverRan();
  }

  @Test
  void aToolCallTurnRunsItsToolOnceAndTheNextTextTurnSucceeds() {
    var model = ScriptedModel.newBuilder().withToolCallsTurn(CALL).withTextTurn("done").build();

    var terminal = run(model, SessionLimits.defaults());

    assertEquals("done", assertInstanceOf(ResultMessage.Success.class, terminal).result());
    assertEquals(1, invocations.get());
  }

  @Test
  void oneAllowedTurnAdmitsAOneTurnAnswer() {
    var model = ScriptedModel.newBuilder().withTextTurn("done").build();

    var terminal = run(model, maxTurns(1));

    assertEquals("done", assertInstanceOf(ResultMessage.Success.class, terminal).result());
  }

  @Test
  void twoAllowedTurnsAdmitAToolTurnAndItsAnswer() {
    var model = ScriptedModel.newBuilder().withToolCallsTurn(CALL).withTextTurn("done").build();

    var terminal = run(model, maxTurns(2));

    assertEquals("done", assertInstanceOf(ResultMessage.Success.class, terminal).result());
    assertEquals(1, invocations.get());
  }

  @Test
  void aToolTurnOnTheLastAllowedTurnRunsItsToolThenEndsAtTheLimit() {
    var model = ScriptedModel.newBuilder().withToolCallsTurn(CALL).withTextTurn("never").build();

    var terminal = run(model, maxTurns(1));

    assertEquals(1, assertInstanceOf(ResultMessage.ErrorMaxTurns.class, terminal).turnsUsed());
    assertEquals(1, invocations.get());
    assertEquals(1, model.calls().size());
  }

  @Test
  void aMessageQueuedDuringTheLastAllowedTurnEndsAtTheLimit() {
    var script = ScriptedModel.newBuilder().withTextTurn("partial").withTextTurn("never").build();
    var model = LoopModels.onEachCall(script, call -> queue.offer(UserMessage.text("more")));

    var terminal = run(model, maxTurns(1));

    assertEquals(1, assertInstanceOf(ResultMessage.ErrorMaxTurns.class, terminal).turnsUsed());
    assertEquals(1, script.calls().size());
  }

  @Test
  void aPreStopInjectOnTheLastAllowedTurnEndsAtTheLimit() {
    var model = ScriptedModel.newBuilder().withTextTurn("draft").withTextTurn("never").build();
    PreStopHook injector = (response, ctx) -> HookOutcome.inject("keep going");

    var terminal = run(model, maxTurns(1), injector);

    assertEquals(1, assertInstanceOf(ResultMessage.ErrorMaxTurns.class, terminal).turnsUsed());
    assertEquals(1, model.calls().size());
  }

  private ResultMessage run(Model model, SessionLimits limits, Hook... hooks) {
    queue.offer(UserMessage.text("go"));
    var terminal = loop(model, hooks).run(state, limits);
    var ended = (QueryEvent.LoopEnded) fixture.events.getLast();
    assertSame(terminal, ended.result());
    return terminal;
  }

  private AgentLoop loop(Model model, Hook... hooks) {
    var tools = new ToolRegistry(List.of(recordingTool()));
    var collaborators =
        fixture.collaborators(new HookRegistry(List.of(hooks)), fixture.dispatch(tools), queue);
    return fixture.loop(
        collaborators, model, TokenCounter.charBased(), ContextCompactor.disabled());
  }

  private ToolBinding recordingTool() {
    var tool =
        Tool.newBuilder()
            .withName(CALL.name())
            .withDescription("counts its invocations")
            .withExecutor(
                (args, ctx) -> ToolResult.success("call " + invocations.incrementAndGet()))
            .build();
    return ToolBinding.newBuilder(tool).withCategory(ToolCategory.WRITE).build();
  }

  private void assertToolNeverRan() {
    assertEquals(0, invocations.get());
    assertTrue(
        fixture.events.stream()
            .noneMatch(e -> e instanceof QueryEvent.ToolUse || e instanceof QueryEvent.ToolResult),
        "a discarded call emits no tool event");
    assertTrue(
        state.history().snapshot().stream().noneMatch(Message::hasToolCalls),
        "a discarded call never reaches the history");
  }

  private static SessionLimits maxTurns(int turns) {
    return SessionLimits.newBuilder().withMaxTurns(turns).build();
  }

  private static Model streaming(Flow.Publisher<ModelChunk> stream) {
    return ScriptedModel.newBuilder().withStreamTurn(stream).build();
  }

  private static Model failingAfterCall(RuntimeException failure) {
    return streaming(ModelStreams.failing(failure, new ModelChunk.ToolUseStop(CALL)));
  }

  /** A stream that delivers the tool call, counts {@code delivered} down and then stays open. */
  private static Flow.Publisher<ModelChunk> openAfterCall(CountDownLatch delivered) {
    var sent = new AtomicInteger();
    return subscriber ->
        subscriber.onSubscribe(
            new Flow.Subscription() {
              @Override
              public void request(long n) {
                if (sent.getAndIncrement() == 0) {
                  subscriber.onNext(new ModelChunk.ToolUseStop(CALL));
                  delivered.countDown();
                }
              }

              @Override
              public void cancel() {}
            });
  }
}
