/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.hooks.HookRegistry;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** End-to-end TurnRunner tests exercising tool dispatch through a real ToolDispatch. */
final class TurnRunnerToolDispatchTest {

  private static final String SID = "sess-tools";

  private final LoopFixture fixture = new LoopFixture(SID, Instant.parse("2026-05-15T08:00:00Z"));
  private final List<QueryEvent> events = fixture.events;
  private final HookRegistry hooks = HookRegistry.empty();
  private final SteeringQueue queue = new SteeringQueue(8);

  @AfterEach
  void closeFixture() {
    fixture.close();
  }

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
    var s = fixture.state();
    s.history().append(Message.user("call echo"));
    s.beginTurn();
    return s;
  }

  private TurnRunner runner(Model model, ToolDispatch dispatch) {
    return fixture.runner(fixture.collaborators(hooks, dispatch, queue), model);
  }

  /** A model whose one turn streams {@code chunks}, then completes. */
  private static Model fixedChunkModel(List<ModelChunk> chunks) {
    return ScriptedModel.newBuilder()
        .withStreamTurn(ModelStreams.of(chunks.toArray(ModelChunk[]::new)))
        .build();
  }

  @Test
  void singleToolCallDispatchesEmitsEventsAppendsMessages() {
    var registry = new ToolRegistry(List.of(echoBinding()));
    var dispatch = fixture.dispatch(registry);
    var call = new ToolCall("call-1", "echo", Map.of("v", "hello"));
    var model =
        fixedChunkModel(
            List.of(
                new ModelChunk.TextDelta("calling..."),
                new ModelChunk.ToolUseStart(call.id(), call.name()),
                new ModelChunk.ToolUseStop(call),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(5, 2), Map.of(), List.of())));
    var runner = runner(model, dispatch);
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
    var history = state.history().snapshot();
    assertEquals(3, history.size());
    var assistant = history.get(1);
    assertEquals(com.standardapplied.helios.core.model.Role.ASSISTANT, assistant.role());
    assertEquals(1, assistant.toolCalls().size());
    var toolMsg = history.get(2);
    assertEquals(com.standardapplied.helios.core.model.Role.TOOL, toolMsg.role());
    assertEquals("echoed: hello", toolMsg.content());
    assertEquals("call-1", toolMsg.toolCallId());
    assertEquals("echo", toolMsg.toolName());
  }

  @Test
  void multipleToolCallsDispatchInOrder() {
    var registry = new ToolRegistry(List.of(echoBinding()));
    var dispatch = fixture.dispatch(registry);
    var c1 = new ToolCall("c1", "echo", Map.of("v", "one"));
    var c2 = new ToolCall("c2", "echo", Map.of("v", "two"));
    var model =
        fixedChunkModel(
            List.of(
                new ModelChunk.ToolUseStop(c1),
                new ModelChunk.ToolUseStop(c2),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0), Map.of(), List.of())));
    var runner = runner(model, dispatch);
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

    var history = state.history().snapshot();
    assertEquals(4, history.size(), "user + assistant + 2 tool messages");
  }

  @Test
  void unknownToolStillCompletesTurnWithFailureResult() {
    var registry = new ToolRegistry(List.of(echoBinding()));
    var dispatch = fixture.dispatch(registry);
    var call = new ToolCall("c1", "nope", Map.of());
    var model =
        fixedChunkModel(
            List.of(
                new ModelChunk.ToolUseStop(call),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0), Map.of(), List.of())));
    var runner = runner(model, dispatch);
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
    var dispatch = fixture.dispatch(registry);
    var call = new ToolCall("c1", "echo", Map.of("v", "hi"));
    // Pre-cancel: dispatch sees the token and throws CancellationException, which TurnRunner
    // catches and converts to a synthetic failure ToolResult.
    token.cancel("user-stop");
    var model =
        fixedChunkModel(
            List.of(
                new ModelChunk.ToolUseStop(call),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0), Map.of(), List.of())));
    var runner = runner(model, dispatch);
    var state = fixture.state(token);
    state.history().append(Message.user("call echo"));
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
    var dispatch = fixture.dispatch(registry);
    var model =
        fixedChunkModel(
            List.of(
                new ModelChunk.TextDelta("just text"),
                new ModelChunk.MessageStop("STOP", Usage.of(2, 2), Map.of(), List.of())));
    var runner = runner(model, dispatch);
    var state = freshState();
    var outcome = runner.runTurn(state, SessionLimits.defaults());

    assertEquals(FinishReason.STOP, outcome.finishReason());
    assertEquals("just text", outcome.assistantContent());
    assertEquals(2, state.history().snapshot().size(), "user + assistant only");
  }

  @Test
  void toolsListPassedToModelMatchesVisibleBindings() {
    var capturedTools = new AtomicReference<List<Tool>>();
    var registry = new ToolRegistry(List.of(echoBinding()));
    var dispatch = fixture.dispatch(registry);
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
            return ModelStreams.of(
                new ModelChunk.TextDelta("ok"),
                new ModelChunk.MessageStop("STOP", Usage.of(0, 0), Map.of(), List.of()));
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
    var runner = runner(model, dispatch);
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
                        List.of(
                            com.standardapplied.helios.core.model.InlineFile.of(
                                pngBytes, "image/png"))))
            .build();
    var binding = ToolBinding.newBuilder(attachmentTool).withCategory(ToolCategory.READ).build();
    var registry = new ToolRegistry(List.of(binding));
    var dispatch = fixture.dispatch(registry);
    var call = new ToolCall("att-1", "returnPng", Map.of());
    var model =
        fixedChunkModel(
            List.of(
                new ModelChunk.ToolUseStop(call),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0), Map.of(), List.of())));
    var runner = runner(model, dispatch);
    var state = freshState();
    runner.runTurn(state, SessionLimits.defaults());

    var history = state.history().snapshot();
    // Expected shape: user("call echo"), assistant(toolCall), tool(text), user(synthetic+attached).
    assertEquals(4, history.size(), "tool result + splice user message must both land in history");
    var toolMsg = history.get(2);
    assertEquals(com.standardapplied.helios.core.model.Role.TOOL, toolMsg.role());
    assertEquals("Returned image/png file (8 bytes) for inspection.", toolMsg.content());

    var splice = history.get(3);
    assertEquals(com.standardapplied.helios.core.model.Role.USER, splice.role());
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
    var dispatch = fixture.dispatch(registry);
    var call = new ToolCall("e", "echo", Map.of("v", "no-attach"));
    var model =
        fixedChunkModel(
            List.of(
                new ModelChunk.ToolUseStop(call),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(0, 0), Map.of(), List.of())));
    var runner = runner(model, dispatch);
    var state = freshState();
    runner.runTurn(state, SessionLimits.defaults());

    var history = state.history().snapshot();
    assertEquals(3, history.size(), "no attachments -> no splice message");
    assertEquals(com.standardapplied.helios.core.model.Role.TOOL, history.get(2).role());
  }
}
