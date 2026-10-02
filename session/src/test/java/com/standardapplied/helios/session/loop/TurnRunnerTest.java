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
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.StopReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class TurnRunnerTest {

  private static final String SID = "sess-1";
  private static final Instant FIXED = Instant.parse("2026-05-14T19:00:00Z");
  private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);

  private final List<QueryEvent> events = new ArrayList<>();
  private final com.standardapplied.helios.session.hooks.HookRegistry hooks =
      com.standardapplied.helios.session.hooks.HookRegistry.empty();
  private final com.standardapplied.helios.session.SteeringQueue queue =
      new com.standardapplied.helios.session.SteeringQueue(8);
  private final ToolDispatch dispatch =
      new ToolDispatch(
          SessionContext.forTesting("turn-runner-test"),
          com.standardapplied.helios.session.tools.ToolRegistry.empty(),
          com.standardapplied.helios.session.ConcurrencyLimits.defaults());

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
          SessionState, com.standardapplied.helios.session.hooks.HookContext>
      CTX_FACTORY =
          s ->
              new com.standardapplied.helios.session.hooks.DefaultHookContext(
                  s.sessionId(), s.currentTurnIndex(), s.cancellation(), CTX_MODEL);

  private SessionState freshState() {
    var s = new SessionState(SID, new CancellationToken(), CLOCK);
    s.appendMessage(Message.user("hello"));
    s.beginTurn();
    return s;
  }

  private static Model textModel(String content, FinishReason finishReason, Usage usage) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder()
            .withContent(content)
            .withFinishReason(finishReason)
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

  private TurnRunner runner(Model model) {
    return new TurnRunner(
        model, hooks, dispatch, queue, events::add, CTX_FACTORY, CLOCK, CostCalculator.ZERO, null);
  }

  @Test
  void nullModelRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new TurnRunner(
                    null,
                    hooks,
                    dispatch,
                    queue,
                    events::add,
                    CTX_FACTORY,
                    CLOCK,
                    CostCalculator.ZERO,
                    null));
    assertEquals("model must not be null", ex.getMessage());
  }

  @Test
  void nullHooksRejected() {
    var model = textModel("x", FinishReason.STOP, Usage.of(1, 1));
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new TurnRunner(
                    model,
                    null,
                    dispatch,
                    queue,
                    events::add,
                    CTX_FACTORY,
                    CLOCK,
                    CostCalculator.ZERO,
                    null));
    assertEquals("hooks must not be null", ex.getMessage());
  }

  @Test
  void nullToolDispatchRejected() {
    var model = textModel("x", FinishReason.STOP, Usage.of(1, 1));
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new TurnRunner(
                    model,
                    hooks,
                    null,
                    queue,
                    events::add,
                    CTX_FACTORY,
                    CLOCK,
                    CostCalculator.ZERO,
                    null));
    assertEquals("toolDispatch must not be null", ex.getMessage());
  }

  @Test
  void nullSteeringQueueRejected() {
    var model = textModel("x", FinishReason.STOP, Usage.of(1, 1));
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new TurnRunner(
                    model,
                    hooks,
                    dispatch,
                    null,
                    events::add,
                    CTX_FACTORY,
                    CLOCK,
                    CostCalculator.ZERO,
                    null));
    assertEquals("steeringQueue must not be null", ex.getMessage());
  }

  @Test
  void nullEventSinkRejected() {
    var model = textModel("x", FinishReason.STOP, Usage.of(1, 1));
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new TurnRunner(
                    model,
                    hooks,
                    dispatch,
                    queue,
                    null,
                    CTX_FACTORY,
                    CLOCK,
                    CostCalculator.ZERO,
                    null));
    assertEquals("eventSink must not be null", ex.getMessage());
  }

  @Test
  void nullHookContextFactoryRejected() {
    var model = textModel("x", FinishReason.STOP, Usage.of(1, 1));
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new TurnRunner(
                    model,
                    hooks,
                    dispatch,
                    queue,
                    events::add,
                    null,
                    CLOCK,
                    CostCalculator.ZERO,
                    null));
    assertEquals("hookContextFactory must not be null", ex.getMessage());
  }

  @Test
  void nullClockRejected() {
    var model = textModel("x", FinishReason.STOP, Usage.of(1, 1));
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new TurnRunner(
                    model,
                    hooks,
                    dispatch,
                    queue,
                    events::add,
                    CTX_FACTORY,
                    null,
                    CostCalculator.ZERO,
                    null));
    assertEquals("clock must not be null", ex.getMessage());
  }

  @Test
  void nullCostCalculatorRejected() {
    var model = textModel("x", FinishReason.STOP, Usage.of(1, 1));
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new TurnRunner(
                    model, hooks, dispatch, queue, events::add, CTX_FACTORY, CLOCK, null, null));
    assertEquals("costCalculator must not be null", ex.getMessage());
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

    var history = state.historySnapshot();
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

    var history = state.historySnapshot();
    assertEquals(1, history.size(), "no assistant message appended for empty content");
  }

  @Test
  void usageAccumulatesToState() {
    var state = freshState();
    runner(textModel("answer", FinishReason.STOP, Usage.of(5, 3)))
        .runTurn(state, SessionLimits.defaults());
    assertEquals(5, state.usage().inputTokens());
    assertEquals(3, state.usage().outputTokens());
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

  /** Build a synchronous publisher that emits the given chunks then onComplete on first request. */
  private static Model syntheticStreamingModel(List<ModelChunk> chunks) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        throw new AssertionError("unused — direct chatStream override");
      }

      @Override
      public Flow.Publisher<ModelChunk> chatStream(
          List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
        return subscriber ->
            subscriber.onSubscribe(
                new Flow.Subscription() {
                  @Override
                  public void request(long n) {
                    for (var c : chunks) {
                      subscriber.onNext(c);
                    }
                    subscriber.onComplete();
                  }

                  @Override
                  public void cancel() {}
                });
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

  @Test
  void thinkingChunksProduceAssistantThinkingEvents() {
    var model =
        syntheticStreamingModel(
            List.of(
                new ModelChunk.ThinkingDelta("planning..."),
                new ModelChunk.TextDelta("answer"),
                new ModelChunk.MessageStop("STOP", Usage.of(4, 2))));
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
    assertTrue(state.historySnapshot().stream().noneMatch(Message::hasToolCalls));
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
                new ModelChunk.MessageStop("STOP", Usage.of(7, 2))));
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
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new AssertionError("unused");
          }

          @Override
          public Flow.Publisher<ModelChunk> chatStream(
              List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
            return subscriber -> {
              subscriber.onSubscribe(
                  new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                      subscriber.onError(new RuntimeException("upstream boom"));
                    }

                    @Override
                    public void cancel() {}
                  });
            };
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
    var outcome = runner(model).runTurn(state, SessionLimits.defaults());
    assertEquals(FinishReason.ERROR, outcome.finishReason());
    assertEquals("upstream boom", outcome.assistantContent());
    assertEquals(1, state.historySnapshot().size(), "error turn does not append assistant message");
    var ended = assertInstanceOf(QueryEvent.TurnEnded.class, events.get(events.size() - 1));
    assertEquals(StopReason.ERROR, ended.reason());
  }

  @Test
  void onErrorWithNullMessageFallsBackToExceptionClassName() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new AssertionError("unused");
          }

          @Override
          public Flow.Publisher<ModelChunk> chatStream(
              List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
            return subscriber -> {
              subscriber.onSubscribe(
                  new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                      subscriber.onError(new RuntimeException());
                    }

                    @Override
                    public void cancel() {}
                  });
            };
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
    var outcome = runner(model).runTurn(freshState(), SessionLimits.defaults());
    assertEquals("RuntimeException", outcome.assistantContent());
  }

  @Test
  void unknownStopReasonStringFallsBackToStop() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new AssertionError("unused");
          }

          @Override
          public Flow.Publisher<ModelChunk> chatStream(
              List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
            return subscriber -> {
              subscriber.onSubscribe(
                  new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                      subscriber.onNext(new ModelChunk.TextDelta("hi"));
                      subscriber.onNext(
                          new ModelChunk.MessageStop("unknown_reason", Usage.of(1, 1)));
                      subscriber.onComplete();
                    }

                    @Override
                    public void cancel() {}
                  });
            };
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
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        throw new AssertionError("unused");
      }

      @Override
      public Flow.Publisher<ModelChunk> chatStream(
          List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
        return subscriber ->
            subscriber.onSubscribe(
                new Flow.Subscription() {
                  @Override
                  public void request(long n) {
                    onRequest.run();
                  }

                  @Override
                  public void cancel() {}
                });
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

  /**
   * Model that records which {@code chatStream} overload was invoked — the untyped variant or the
   * typed-with-schema variant — and replays a single-text-delta turn either way. Lets a test assert
   * that {@link TurnRunner} picks the typed dispatch precisely when {@code outputSchema} is
   * non-null at construction time.
   */
  private static final class DispatchRecordingModel implements Model {
    final AtomicReference<com.standardapplied.helios.core.schema.OutputSchema<?>> seenSchema =
        new AtomicReference<>();
    final java.util.concurrent.atomic.AtomicBoolean typedDispatch =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    final java.util.concurrent.atomic.AtomicBoolean untypedDispatch =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public Response<Void> chat(List<Message> messages, List<Tool> tools) {
      return Response.newBuilder().withContent("untyped").build();
    }

    @Override
    public Flow.Publisher<ModelChunk> chatStream(
        List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
      untypedDispatch.set(true);
      return chunksOnce("untyped");
    }

    @Override
    public Flow.Publisher<ModelChunk> chatStream(
        List<Message> messages,
        List<Tool> tools,
        com.standardapplied.helios.core.schema.OutputSchema<?> outputSchema,
        CancellationToken cancellation) {
      typedDispatch.set(true);
      seenSchema.set(outputSchema);
      return chunksOnce("typed-with-schema");
    }

    private static Flow.Publisher<ModelChunk> chunksOnce(String content) {
      return subscriber -> {
        subscriber.onSubscribe(
            new Flow.Subscription() {
              private int i = 0;

              @Override
              public void request(long n) {
                if (i == 0) {
                  subscriber.onNext(new ModelChunk.TextDelta(content));
                  i = 1;
                }
                if (i == 1) {
                  subscriber.onNext(
                      new ModelChunk.MessageStop(
                          FinishReason.STOP.name(), Usage.of(1, 1), Map.of()));
                  i = 2;
                  subscriber.onComplete();
                }
              }

              @Override
              public void cancel() {}
            });
      };
    }

    @Override
    public String id() {
      return "dispatch-recorder";
    }

    @Override
    public String provider() {
      return "test";
    }
  }

  @Test
  void dispatchUsesUntypedChatStreamWhenOutputSchemaIsNull() {
    var model = new DispatchRecordingModel();
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
    runner.runTurn(freshState(), SessionLimits.defaults());
    assertTrue(model.untypedDispatch.get(), "no outputSchema configured: must use untyped path");
    assertEquals(false, model.typedDispatch.get(), "typed dispatch must not fire");
  }

  @Test
  void dispatchUsesTypedChatStreamWhenOutputSchemaIsConfigured() {
    var schema = com.standardapplied.helios.core.schema.OutputSchema.of(Sample.class);
    var model = new DispatchRecordingModel();
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
            schema);
    runner.runTurn(freshState(), SessionLimits.defaults());
    assertTrue(model.typedDispatch.get(), "outputSchema set: must use typed-with-schema path");
    assertEquals(
        false,
        model.untypedDispatch.get(),
        "untyped dispatch must not fire — that's the bug the wiring fixes");
    assertEquals(
        schema,
        model.seenSchema.get(),
        "schema delivered to the provider must be the configured one");
  }

  /**
   * Defensive: when {@link com.standardapplied.helios.core.schema.StructuredOutputParseException}
   * fires <i>and</i> the steering queue is full so the correction message can't be enqueued, the
   * runner must let the underlying parse error surface (returns {@link FinishReason#ERROR}) rather
   * than silently swallowing it.
   */
  @Test
  void parseFailureWithFullSteeringQueueFallsThroughToErrorOutcome() {
    var schema = com.standardapplied.helios.core.schema.OutputSchema.of(Sample.class);
    var saturatedQueue = new com.standardapplied.helios.session.SteeringQueue(1);
    saturatedQueue.offer(com.standardapplied.helios.session.UserMessage.text("pre-existing"));
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder().build();
          }

          @Override
          public Flow.Publisher<ModelChunk> chatStream(
              List<Message> messages,
              List<Tool> tools,
              com.standardapplied.helios.core.schema.OutputSchema<?> outputSchema,
              CancellationToken cancellation) {
            return subscriber ->
                subscriber.onSubscribe(
                    new Flow.Subscription() {
                      @Override
                      public void request(long n) {
                        subscriber.onError(
                            new com.standardapplied.helios.core.schema
                                .StructuredOutputParseException(
                                List.of("field is required"), "{\"wrong\":\"shape\"}"));
                      }

                      @Override
                      public void cancel() {}
                    });
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
    var runner =
        new TurnRunner(
            model,
            hooks,
            dispatch,
            saturatedQueue,
            events::add,
            CTX_FACTORY,
            CLOCK,
            CostCalculator.ZERO,
            schema);
    var outcome = runner.runTurn(freshState(), SessionLimits.defaults());
    assertEquals(
        FinishReason.ERROR,
        outcome.finishReason(),
        "queue-full fallthrough: parse error must surface, not be silently swallowed");
  }
}
