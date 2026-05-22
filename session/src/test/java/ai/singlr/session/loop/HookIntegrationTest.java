/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.CostCalculator;
import ai.singlr.core.context.TokenCounter;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelChunk;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.runtime.SessionContext;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.ConcurrencyLimits;
import ai.singlr.session.ContextCompactor;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.ResultMessage;
import ai.singlr.session.SessionLimits;
import ai.singlr.session.SteeringQueue;
import ai.singlr.session.UserMessage;
import ai.singlr.session.hooks.DefaultHookContext;
import ai.singlr.session.hooks.HookContext;
import ai.singlr.session.hooks.HookOutcome;
import ai.singlr.session.hooks.HookRegistry;
import ai.singlr.session.hooks.OnUserMessageHook;
import ai.singlr.session.hooks.PostModelTurnHook;
import ai.singlr.session.hooks.PostToolUseHook;
import ai.singlr.session.hooks.PreModelTurnHook;
import ai.singlr.session.hooks.PreStopHook;
import ai.singlr.session.hooks.PreToolUseHook;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolCategory;
import ai.singlr.session.tools.ToolRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** End-to-end tests covering the hook outcome handling wired through AgentLoop + TurnRunner. */
final class HookIntegrationTest {

  private static final String SID = "sess-hook";
  private static final Instant FIXED = Instant.parse("2026-05-15T09:00:00Z");
  private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);

  private static Model CTX_MODEL =
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

  private static Function<SessionState, HookContext> contextFactory() {
    return s ->
        new DefaultHookContext(s.sessionId(), s.currentTurnIndex(), s.cancellation(), CTX_MODEL);
  }

  private static Tool echoTool() {
    return Tool.newBuilder()
        .withName("echo")
        .withDescription("echo")
        .withExecutor((args, ctx) -> ToolResult.success("echoed: " + args.get("v")))
        .build();
  }

  private static ToolBinding echoBinding() {
    return ToolBinding.newBuilder(echoTool()).withCategory(ToolCategory.READ).build();
  }

  /** A model that streams a fixed sequence of chunks per call (cycles by call index). */
  private static Model scriptedModel(List<List<ModelChunk>> perCallScript) {
    var calls = new AtomicInteger();
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        throw new AssertionError("unused");
      }

      @Override
      public Flow.Publisher<ModelChunk> chatStream(
          List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
        var idx = calls.getAndIncrement();
        var chunks = perCallScript.get(Math.min(idx, perCallScript.size() - 1));
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
        return "scripted";
      }

      @Override
      public String provider() {
        return "scripted";
      }
    };
  }

  private static List<QueryEvent> events = new ArrayList<>();

  private static AgentLoop buildLoop(
      Model model, ToolRegistry toolRegistry, HookRegistry hooks, SteeringQueue queue) {
    events = new ArrayList<>();
    var dispatch =
        new ToolDispatch(
            SessionContext.forTesting("hook-integration"),
            toolRegistry,
            ConcurrencyLimits.defaults());
    var runner =
        new TurnRunner(
            model,
            hooks,
            dispatch,
            queue,
            events::add,
            contextFactory(),
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
        contextFactory(),
        CLOCK,
        TokenCounter.charBased(),
        ContextCompactor.disabled());
  }

  private static SessionState freshState() {
    return new SessionState(SID, new CancellationToken(), CLOCK);
  }

  // ── OnUserMessage outcomes ────────────────────────────────────────────────

  @Test
  void onUserMessageBlockDropsTheMessage() {
    OnUserMessageHook blocker = (msg, ctx) -> HookOutcome.block("PII");
    var hooks = new HookRegistry(List.of(blocker));
    var queue = new SteeringQueue(8);
    var dropped = UserMessage.text("secret");
    queue.offer(dropped);
    var model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelChunk.TextDelta("never"),
                    new ModelChunk.MessageStop("STOP", Usage.of(0, 0)))));
    var result =
        buildLoop(model, ToolRegistry.empty(), hooks, queue)
            .run(freshState(), SessionLimits.defaults());
    // Message blocked → history stays empty → loop terminates with EmptyHistory error.
    assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result);
    assertFalse(events.stream().anyMatch(e -> e instanceof QueryEvent.UserMessageReceived));
    assertTrue(
        events.stream()
            .filter(e -> e instanceof QueryEvent.HookFired)
            .map(e -> (QueryEvent.HookFired) e)
            .anyMatch(h -> h.outcomeKind().equals("Block")));
    // The dropped message surfaces as MessageBlocked so UIs/audit can render the drop.
    var blocked =
        events.stream()
            .filter(e -> e instanceof QueryEvent.MessageBlocked)
            .map(e -> (QueryEvent.MessageBlocked) e)
            .findFirst()
            .orElseThrow();
    assertSame(dropped, blocked.message());
    assertEquals("PII", blocked.reason());
    assertFalse(blocked.hookName().isBlank(), "hookName must be non-blank");
  }

  @Test
  void onUserMessageMutateRewritesText() {
    OnUserMessageHook rewriter = (msg, ctx) -> HookOutcome.mutate(Map.of("text", "REDACTED"));
    var hooks = new HookRegistry(List.of(rewriter));
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("PII data"));
    var model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelChunk.TextDelta("ok"),
                    new ModelChunk.MessageStop("STOP", Usage.of(0, 0)))));
    var state = freshState();
    buildLoop(model, ToolRegistry.empty(), hooks, queue).run(state, SessionLimits.defaults());
    // History's user message should carry the rewritten text.
    assertEquals("REDACTED", state.historySnapshot().get(0).content());
  }

  @Test
  void onUserMessageStopTerminatesSession() {
    OnUserMessageHook stopper = (msg, ctx) -> HookOutcome.stop("blocked-by-hook");
    var hooks = new HookRegistry(List.of(stopper));
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    var model = scriptedModel(List.of(List.of(new ModelChunk.MessageStop("STOP", Usage.of(0, 0)))));
    var result =
        buildLoop(model, ToolRegistry.empty(), hooks, queue)
            .run(freshState(), SessionLimits.defaults());
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("blocked-by-hook", success.result());
  }

  // ── PreStop outcomes ──────────────────────────────────────────────────────

  @Test
  void preStopInjectQueuesMessageAndContinuesLoop() {
    var calls = new AtomicInteger();
    PreStopHook injector =
        (response, ctx) ->
            calls.incrementAndGet() == 1 ? HookOutcome.inject("more please") : HookOutcome.cont();
    var hooks = new HookRegistry(List.of(injector));
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("first"));
    var model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelChunk.TextDelta("draft"),
                    new ModelChunk.MessageStop("STOP", Usage.of(1, 1))),
                List.of(
                    new ModelChunk.TextDelta("final"),
                    new ModelChunk.MessageStop("STOP", Usage.of(1, 1)))));
    var result =
        buildLoop(model, ToolRegistry.empty(), hooks, queue)
            .run(freshState(), SessionLimits.defaults());
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("final", success.result(), "second turn drove the terminal success");
  }

  @Test
  void preStopStopOverridesResultText() {
    PreStopHook overrider = (response, ctx) -> HookOutcome.stop("override-text");
    var hooks = new HookRegistry(List.of(overrider));
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    var model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelChunk.TextDelta("draft"),
                    new ModelChunk.MessageStop("STOP", Usage.of(1, 1)))));
    var result =
        buildLoop(model, ToolRegistry.empty(), hooks, queue)
            .run(freshState(), SessionLimits.defaults());
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("override-text", success.result());
  }

  // ── PreModelTurn outcomes ─────────────────────────────────────────────────

  @Test
  void preModelTurnInjectSkipsTheModelCallForThatTurn() {
    var calls = new AtomicInteger();
    PreModelTurnHook injector =
        (history, ctx) ->
            calls.incrementAndGet() == 1 ? HookOutcome.inject("after-inject") : HookOutcome.cont();
    var hooks = new HookRegistry(List.of(injector));
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("first"));
    var modelCalls = new AtomicInteger();
    Model countingModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new AssertionError("unused");
          }

          @Override
          public Flow.Publisher<ModelChunk> chatStream(
              List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
            modelCalls.incrementAndGet();
            return subscriber ->
                subscriber.onSubscribe(
                    new Flow.Subscription() {
                      @Override
                      public void request(long n) {
                        subscriber.onNext(new ModelChunk.TextDelta("ok"));
                        subscriber.onNext(new ModelChunk.MessageStop("STOP", Usage.of(0, 0)));
                        subscriber.onComplete();
                      }

                      @Override
                      public void cancel() {}
                    });
          }

          @Override
          public String id() {
            return "counting";
          }

          @Override
          public String provider() {
            return "counting";
          }
        };
    var result =
        buildLoop(countingModel, ToolRegistry.empty(), hooks, queue)
            .run(freshState(), SessionLimits.defaults());
    assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals(1, modelCalls.get(), "first turn skipped, second turn called the model once");
  }

  @Test
  void preModelTurnStopTerminates() {
    PreModelTurnHook stopper = (history, ctx) -> HookOutcome.stop("pre-turn-stop");
    var hooks = new HookRegistry(List.of(stopper));
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    var model = scriptedModel(List.of(List.of(new ModelChunk.MessageStop("STOP", Usage.of(0, 0)))));
    var result =
        buildLoop(model, ToolRegistry.empty(), hooks, queue)
            .run(freshState(), SessionLimits.defaults());
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("pre-turn-stop", success.result());
  }

  // ── PostModelTurn outcomes ────────────────────────────────────────────────

  @Test
  void postModelTurnInjectQueuesAndContinues() {
    var calls = new AtomicInteger();
    PostModelTurnHook injector =
        (response, ctx) ->
            calls.incrementAndGet() == 1 ? HookOutcome.inject("revise") : HookOutcome.cont();
    var hooks = new HookRegistry(List.of(injector));
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    var model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelChunk.TextDelta("v1"),
                    new ModelChunk.MessageStop("STOP", Usage.of(1, 1))),
                List.of(
                    new ModelChunk.TextDelta("v2"),
                    new ModelChunk.MessageStop("STOP", Usage.of(1, 1)))));
    var result =
        buildLoop(model, ToolRegistry.empty(), hooks, queue)
            .run(freshState(), SessionLimits.defaults());
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("v2", success.result());
  }

  // ── PreToolUse outcomes ───────────────────────────────────────────────────

  @Test
  void preToolUseBlockEmitsToolBlockedAndSubstitutesFailureResult() {
    PreToolUseHook blocker = (call, ctx) -> HookOutcome.block("not allowed");
    var hooks = new HookRegistry(List.of(blocker));
    var tools = new ToolRegistry(List.of(echoBinding()));
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("call echo"));
    var call = new ToolCall("c1", "echo", Map.of("v", "x"));
    var model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelChunk.ToolUseStop(call),
                    new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0))),
                List.of(
                    new ModelChunk.TextDelta("after-block"),
                    new ModelChunk.MessageStop("STOP", Usage.of(0, 0)))));
    var result = buildLoop(model, tools, hooks, queue).run(freshState(), SessionLimits.defaults());
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("after-block", success.result());
    var blocked =
        events.stream()
            .filter(e -> e instanceof QueryEvent.ToolBlocked)
            .map(e -> (QueryEvent.ToolBlocked) e)
            .findFirst()
            .orElseThrow();
    assertEquals("not allowed", blocked.reason());
    var toolResult =
        events.stream()
            .filter(e -> e instanceof QueryEvent.ToolResult)
            .map(e -> (QueryEvent.ToolResult) e)
            .findFirst()
            .orElseThrow();
    assertTrue(toolResult.result().output().contains("blocked by hook"));
  }

  @Test
  void preToolUseMutateRewritesArgsAndEmitsToolMutated() {
    PreToolUseHook rewriter = (call, ctx) -> HookOutcome.mutate(Map.of("v", "mutated"));
    var hooks = new HookRegistry(List.of(rewriter));
    var tools = new ToolRegistry(List.of(echoBinding()));
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("call echo"));
    var call = new ToolCall("c1", "echo", Map.of("v", "original"));
    var model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelChunk.ToolUseStop(call),
                    new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0))),
                List.of(
                    new ModelChunk.TextDelta("done"),
                    new ModelChunk.MessageStop("STOP", Usage.of(0, 0)))));
    var result = buildLoop(model, tools, hooks, queue).run(freshState(), SessionLimits.defaults());
    assertInstanceOf(ResultMessage.Success.class, result);
    var mutated =
        events.stream()
            .filter(e -> e instanceof QueryEvent.ToolMutated)
            .map(e -> (QueryEvent.ToolMutated) e)
            .findFirst()
            .orElseThrow();
    assertEquals("original", mutated.inputBefore().get("v"));
    assertEquals("mutated", mutated.inputAfter().get("v"));
    var toolResult =
        events.stream()
            .filter(e -> e instanceof QueryEvent.ToolResult)
            .map(e -> (QueryEvent.ToolResult) e)
            .findFirst()
            .orElseThrow();
    assertEquals("echoed: mutated", toolResult.result().output());
  }

  @Test
  void preToolUseStopTerminates() {
    PreToolUseHook stopper = (call, ctx) -> HookOutcome.stop("pre-tool-stop");
    var hooks = new HookRegistry(List.of(stopper));
    var tools = new ToolRegistry(List.of(echoBinding()));
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("call echo"));
    var call = new ToolCall("c1", "echo", Map.of());
    var model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelChunk.ToolUseStop(call),
                    new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0)))));
    var result = buildLoop(model, tools, hooks, queue).run(freshState(), SessionLimits.defaults());
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("pre-tool-stop", success.result());
  }

  // ── PostToolUse outcomes ─────────────────────────────────────────────────

  @Test
  void postToolUseMutateRewritesResultOutput() {
    PostToolUseHook rewriter =
        (call, result, ctx) -> HookOutcome.mutate(Map.of("output", "REWRITTEN"));
    var hooks = new HookRegistry(List.of(rewriter));
    var tools = new ToolRegistry(List.of(echoBinding()));
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("call echo"));
    var call = new ToolCall("c1", "echo", Map.of("v", "x"));
    var model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelChunk.ToolUseStop(call),
                    new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0))),
                List.of(
                    new ModelChunk.TextDelta("done"),
                    new ModelChunk.MessageStop("STOP", Usage.of(0, 0)))));
    var state = freshState();
    buildLoop(model, tools, hooks, queue).run(state, SessionLimits.defaults());

    // History's tool message should carry the REWRITTEN output.
    var history = state.historySnapshot();
    var toolMsg =
        history.stream()
            .filter(m -> m.role() == ai.singlr.core.model.Role.TOOL)
            .findFirst()
            .orElseThrow();
    assertEquals("REWRITTEN", toolMsg.content());
  }

  @Test
  void preToolUseInjectSubstitutesFailureAndQueuesMessage() {
    PreToolUseHook injector = (call, ctx) -> HookOutcome.inject("review args");
    var hooks = new HookRegistry(List.of(injector));
    var tools = new ToolRegistry(List.of(echoBinding()));
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("call echo"));
    var call = new ToolCall("c1", "echo", Map.of());
    var model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelChunk.ToolUseStop(call),
                    new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0))),
                List.of(
                    new ModelChunk.TextDelta("after-inject"),
                    new ModelChunk.MessageStop("STOP", Usage.of(0, 0)))));
    var result = buildLoop(model, tools, hooks, queue).run(freshState(), SessionLimits.defaults());
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("after-inject", success.result());
    var toolResult =
        events.stream()
            .filter(e -> e instanceof QueryEvent.ToolResult)
            .map(e -> (QueryEvent.ToolResult) e)
            .findFirst()
            .orElseThrow();
    assertTrue(toolResult.result().output().contains("hook injected"));
  }

  @Test
  void postToolUseInjectQueuesMessageButLoopContinues() {
    var calls = new AtomicInteger();
    PostToolUseHook injector =
        (call, result, ctx) ->
            calls.incrementAndGet() == 1 ? HookOutcome.inject("retry") : HookOutcome.cont();
    var hooks = new HookRegistry(List.of(injector));
    var tools = new ToolRegistry(List.of(echoBinding()));
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("call echo"));
    var call = new ToolCall("c1", "echo", Map.of("v", "x"));
    var model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelChunk.ToolUseStop(call),
                    new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0))),
                List.of(
                    new ModelChunk.TextDelta("done"),
                    new ModelChunk.MessageStop("STOP", Usage.of(0, 0)))));
    var result = buildLoop(model, tools, hooks, queue).run(freshState(), SessionLimits.defaults());
    assertInstanceOf(ResultMessage.Success.class, result);
  }

  @Test
  void postToolUseStopTerminatesAfterToolMessageAppended() {
    PostToolUseHook stopper = (call, result, ctx) -> HookOutcome.stop("after-tool-stop");
    var hooks = new HookRegistry(List.of(stopper));
    var tools = new ToolRegistry(List.of(echoBinding()));
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("call echo"));
    var call = new ToolCall("c1", "echo", Map.of("v", "x"));
    var model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelChunk.ToolUseStop(call),
                    new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0)))));
    var state = freshState();
    var result = buildLoop(model, tools, hooks, queue).run(state, SessionLimits.defaults());
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("after-tool-stop", success.result());
    // Tool message was appended before the Stop fired
    var toolMsg =
        state.historySnapshot().stream()
            .filter(m -> m.role() == ai.singlr.core.model.Role.TOOL)
            .findFirst()
            .orElseThrow();
    assertEquals("echoed: x", toolMsg.content());
  }

  // ── HookFired event ──────────────────────────────────────────────────────

  @Test
  void hookFiredCarriesActualHookName() {
    var named =
        new PreToolUseHook() {
          @Override
          public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
            return HookOutcome.block("nope");
          }

          @Override
          public String name() {
            return "MySecurityGuard";
          }
        };
    var hooks = new HookRegistry(List.of(named));
    var tools = new ToolRegistry(List.of(echoBinding()));
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("call echo"));
    var call = new ToolCall("c1", "echo", Map.of("v", "x"));
    var model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelChunk.ToolUseStop(call),
                    new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0))),
                List.of(
                    new ModelChunk.TextDelta("done"),
                    new ModelChunk.MessageStop("STOP", Usage.of(0, 0)))));
    buildLoop(model, tools, hooks, queue).run(freshState(), SessionLimits.defaults());
    var hookFired =
        events.stream()
            .filter(e -> e instanceof QueryEvent.HookFired)
            .map(e -> (QueryEvent.HookFired) e)
            .findFirst()
            .orElseThrow();
    assertEquals("MySecurityGuard", hookFired.hookName(), "hookName must carry the hook's name");
    assertEquals("PreToolUseHook", hookFired.phase(), "phase must carry the lifecycle phase");
    var blocked =
        events.stream()
            .filter(e -> e instanceof QueryEvent.ToolBlocked)
            .map(e -> (QueryEvent.ToolBlocked) e)
            .findFirst()
            .orElseThrow();
    assertEquals(
        "MySecurityGuard", blocked.hookName(), "ToolBlocked.hookName must carry the hook's name");
  }

  @Test
  void continueOutcomesDoNotEmitHookFired() {
    OnUserMessageHook noOp = (msg, ctx) -> HookOutcome.cont();
    PreModelTurnHook noOp2 = (history, ctx) -> HookOutcome.cont();
    var hooks = new HookRegistry(List.of(noOp, noOp2));
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    var model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelChunk.TextDelta("ok"),
                    new ModelChunk.MessageStop("STOP", Usage.of(0, 0)))));
    buildLoop(model, ToolRegistry.empty(), hooks, queue)
        .run(freshState(), SessionLimits.defaults());
    assertFalse(
        events.stream().anyMatch(e -> e instanceof QueryEvent.HookFired),
        "Continue outcomes must not emit HookFired");
  }
}
