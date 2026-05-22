/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.CostCalculator;
import ai.singlr.core.model.FinishReason;
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
import ai.singlr.session.QueryEvent;
import ai.singlr.session.SessionLimits;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** End-to-end TurnRunner tests exercising tool dispatch through a real ToolDispatch. */
final class TurnRunnerToolDispatchTest {

  private static final String SID = "sess-tools";
  private static final SessionContext CTX = SessionContext.forTesting(SID);
  private static final Instant FIXED = Instant.parse("2026-05-15T08:00:00Z");
  private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);

  private final List<QueryEvent> events = new ArrayList<>();
  private final ai.singlr.session.hooks.HookRegistry hooks =
      ai.singlr.session.hooks.HookRegistry.empty();
  private final ai.singlr.session.SteeringQueue queue = new ai.singlr.session.SteeringQueue(8);

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

  private static Tool echoTool() {
    return Tool.newBuilder()
        .withName("echo")
        .withDescription("returns its 'v' arg")
        .withExecutor((args, ctx) -> ToolResult.success("echoed: " + args.get("v")))
        .build();
  }

  private static ToolBinding echoBinding() {
    return ToolBinding.newBuilder(echoTool()).withCategory(ToolCategory.READ).build();
  }

  private SessionState freshState() {
    var s = new SessionState(SID, new CancellationToken(), CLOCK);
    s.appendMessage(Message.user("call echo"));
    s.beginTurn();
    return s;
  }

  /** A model that streams a fixed sequence of chunks. */
  private static Model fixedChunkModel(List<ModelChunk> chunks) {
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
  void singleToolCallDispatchesEmitsEventsAppendsMessages() {
    var registry = new ToolRegistry(List.of(echoBinding()));
    var dispatch = new ToolDispatch(CTX, registry, ConcurrencyLimits.defaults());
    var call = new ToolCall("call-1", "echo", Map.of("v", "hello"));
    var model =
        fixedChunkModel(
            List.of(
                new ModelChunk.TextDelta("calling..."),
                new ModelChunk.ToolUseStart(call.id(), call.name()),
                new ModelChunk.ToolUseStop(call),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(5, 2))));
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
    var state = freshState();
    var outcome = runner.runTurn(state, SessionLimits.defaults());

    // Outcome forced to TOOL_CALLS because tool calls were dispatched.
    assertEquals(FinishReason.TOOL_CALLS, outcome.finishReason());

    // Events: AssistantText, ToolUse, ToolResult, TurnEnded.
    var toolUse =
        events.stream().filter(e -> e instanceof QueryEvent.ToolUse).findFirst().orElseThrow();
    var toolResultEvt =
        events.stream().filter(e -> e instanceof QueryEvent.ToolResult).findFirst().orElseThrow();
    assertSame(call, ((QueryEvent.ToolUse) toolUse).call());
    assertEquals("echoed: hello", ((QueryEvent.ToolResult) toolResultEvt).result().output());

    // History: user, assistant(with toolCalls), tool(echoed: hello).
    var history = state.historySnapshot();
    assertEquals(3, history.size());
    var assistant = history.get(1);
    assertEquals(ai.singlr.core.model.Role.ASSISTANT, assistant.role());
    assertEquals(1, assistant.toolCalls().size());
    var toolMsg = history.get(2);
    assertEquals(ai.singlr.core.model.Role.TOOL, toolMsg.role());
    assertEquals("echoed: hello", toolMsg.content());
    assertEquals("call-1", toolMsg.toolCallId());
    assertEquals("echo", toolMsg.toolName());
  }

  @Test
  void multipleToolCallsDispatchInOrder() {
    var registry = new ToolRegistry(List.of(echoBinding()));
    var dispatch = new ToolDispatch(CTX, registry, ConcurrencyLimits.defaults());
    var c1 = new ToolCall("c1", "echo", Map.of("v", "one"));
    var c2 = new ToolCall("c2", "echo", Map.of("v", "two"));
    var model =
        fixedChunkModel(
            List.of(
                new ModelChunk.ToolUseStop(c1),
                new ModelChunk.ToolUseStop(c2),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0))));
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
    var state = freshState();
    runner.runTurn(state, SessionLimits.defaults());

    var toolResults =
        events.stream()
            .filter(e -> e instanceof QueryEvent.ToolResult)
            .map(e -> (QueryEvent.ToolResult) e)
            .toList();
    assertEquals(2, toolResults.size());
    assertEquals("echoed: one", toolResults.get(0).result().output());
    assertEquals("echoed: two", toolResults.get(1).result().output());

    var history = state.historySnapshot();
    assertEquals(4, history.size(), "user + assistant + 2 tool messages");
  }

  @Test
  void unknownToolStillCompletesTurnWithFailureResult() {
    var registry = new ToolRegistry(List.of(echoBinding()));
    var dispatch = new ToolDispatch(CTX, registry, ConcurrencyLimits.defaults());
    var call = new ToolCall("c1", "nope", Map.of());
    var model =
        fixedChunkModel(
            List.of(
                new ModelChunk.ToolUseStop(call),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0))));
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
    var state = freshState();
    var outcome = runner.runTurn(state, SessionLimits.defaults());

    assertEquals(FinishReason.TOOL_CALLS, outcome.finishReason());
    var toolResult =
        (QueryEvent.ToolResult)
            events.stream()
                .filter(e -> e instanceof QueryEvent.ToolResult)
                .findFirst()
                .orElseThrow();
    assertTrue(toolResult.result().output().contains("tool not found"));
  }

  @Test
  void cancellationDuringDispatchSurfacesAsFailure() {
    var token = new CancellationToken();
    var registry = new ToolRegistry(List.of(echoBinding()));
    var dispatch = new ToolDispatch(CTX, registry, ConcurrencyLimits.defaults());
    var call = new ToolCall("c1", "echo", Map.of("v", "hi"));
    // Pre-cancel: dispatch sees the token and throws CancellationException, which TurnRunner
    // catches and converts to a synthetic failure ToolResult.
    token.cancel("user-stop");
    var model =
        fixedChunkModel(
            List.of(
                new ModelChunk.ToolUseStop(call),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0))));
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
    var state = new SessionState(SID, token, CLOCK);
    state.appendMessage(Message.user("call echo"));
    state.beginTurn();
    runner.runTurn(state, SessionLimits.defaults());

    var toolResult =
        (QueryEvent.ToolResult)
            events.stream()
                .filter(e -> e instanceof QueryEvent.ToolResult)
                .findFirst()
                .orElseThrow();
    assertTrue(toolResult.result().output().startsWith("tool dispatch failed:"));
  }

  @Test
  void textOnlyTurnUnchanged() {
    var registry = ToolRegistry.empty();
    var dispatch = new ToolDispatch(CTX, registry, ConcurrencyLimits.defaults());
    var model =
        fixedChunkModel(
            List.of(
                new ModelChunk.TextDelta("just text"),
                new ModelChunk.MessageStop("STOP", Usage.of(2, 2))));
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
    var state = freshState();
    var outcome = runner.runTurn(state, SessionLimits.defaults());

    assertEquals(FinishReason.STOP, outcome.finishReason());
    assertEquals("just text", outcome.assistantContent());
    assertEquals(2, state.historySnapshot().size(), "user + assistant only");
  }

  @Test
  void toolsListPassedToModelMatchesVisibleBindings() {
    var capturedTools = new AtomicReference<List<Tool>>();
    var registry = new ToolRegistry(List.of(echoBinding()));
    var dispatch = new ToolDispatch(CTX, registry, ConcurrencyLimits.defaults());
    Model model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new AssertionError("unused");
          }

          @Override
          public Flow.Publisher<ModelChunk> chatStream(
              List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
            capturedTools.set(tools);
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
            queue,
            events::add,
            CTX_FACTORY,
            CLOCK,
            CostCalculator.ZERO,
            null);
    runner.runTurn(freshState(), SessionLimits.defaults());

    assertEquals(1, capturedTools.get().size());
    assertEquals("echo", capturedTools.get().get(0).name());
  }

  @Test
  void toolReturningAttachmentsAppendsFollowupUserMessageWithInlineFiles() {
    // Verifies the Layer 2 loop-splice contract: when a tool returns ToolResult attachments,
    // the loop appends a synthetic user message AFTER the tool-result message so the next turn's
    // provider call carries the InlineFiles through the standard user-message multimodal path.
    // Without this splice the bytes never reach the provider's vision/PDF channel.
    var pngBytes = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3};
    var attachmentTool =
        Tool.newBuilder()
            .withName("returnPng")
            .withDescription("returns a tiny PNG as an attachment")
            .withExecutor(
                (args, ctx) ->
                    ToolResult.successWithAttachments(
                        "Returned image/png file (8 bytes) for inspection.",
                        List.of(ai.singlr.core.model.InlineFile.of(pngBytes, "image/png"))))
            .build();
    var binding = ToolBinding.newBuilder(attachmentTool).withCategory(ToolCategory.READ).build();
    var registry = new ToolRegistry(List.of(binding));
    var dispatch = new ToolDispatch(CTX, registry, ConcurrencyLimits.defaults());
    var call = new ToolCall("att-1", "returnPng", Map.of());
    var model =
        fixedChunkModel(
            List.of(
                new ModelChunk.ToolUseStop(call),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0))));
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
    var state = freshState();
    runner.runTurn(state, SessionLimits.defaults());

    var history = state.historySnapshot();
    // Expected shape: user("call echo"), assistant(toolCall), tool(text), user(synthetic+attached).
    assertEquals(4, history.size(), "tool result + splice user message must both land in history");
    var toolMsg = history.get(2);
    assertEquals(ai.singlr.core.model.Role.TOOL, toolMsg.role());
    assertEquals("Returned image/png file (8 bytes) for inspection.", toolMsg.content());

    var splice = history.get(3);
    assertEquals(ai.singlr.core.model.Role.USER, splice.role());
    assertTrue(
        splice.content().contains("tool 'returnPng' returned 1 attachment"),
        "splice user message must name the tool and attachment count: " + splice.content());
    assertEquals(1, splice.inlineFiles().size());
    assertEquals("image/png", splice.inlineFiles().getFirst().mimeType());
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        pngBytes,
        splice.inlineFiles().getFirst().data(),
        "the PNG bytes must reach the next turn unchanged");
  }

  @Test
  void toolReturningNoAttachmentsLeavesConversationShapeUnchanged() {
    // Sanity: when no attachments, no synthetic user message is appended. The shape is the
    // pre-Layer-2 default (user + assistant + tool).
    var registry = new ToolRegistry(List.of(echoBinding()));
    var dispatch = new ToolDispatch(CTX, registry, ConcurrencyLimits.defaults());
    var call = new ToolCall("e", "echo", Map.of("v", "no-attach"));
    var model =
        fixedChunkModel(
            List.of(
                new ModelChunk.ToolUseStop(call),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0))));
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
    var state = freshState();
    runner.runTurn(state, SessionLimits.defaults());

    var history = state.historySnapshot();
    assertEquals(3, history.size(), "no attachments -> no splice message");
    assertEquals(ai.singlr.core.model.Role.TOOL, history.get(2).role());
  }
}
