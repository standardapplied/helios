/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * The session's compactor through the loop: the history crosses the compaction watermark after the
 * first tool round. The default compactor leaves the next model request with no earlier assistant
 * turn, a compactor wrapping it sees the history on both sides, and the disabled one leaves the
 * whole conversation.
 */
class DefaultCompactionSessionTest {

  @Test
  void theRequestAfterACompactionIsTheSystemPromptAndTheSummary() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(new ToolCall("c1", "echo", Map.of()))
            .withTextTurn("the gist")
            .withTextTurn("done")
            .build();

    var events = runToSuccess(compactingAfterTheFirstToolResult(model));

    assertEquals(
        List.of(
            Message.system("sys"), Message.user(SummarizingCompactor.SUMMARY_PREFIX + "the gist")),
        model.calls().getLast());
    assertEquals(3, model.calls().size());
    assertEquals(1, events.eventsOf(QueryEvent.ContextEdited.class).size());
  }

  @Test
  void aCompactorWrappingTheDefaultSeesTheHistoryBeforeAndTheResultAfter() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(new ToolCall("c1", "echo", Map.of()))
            .withTextTurn("the gist")
            .withTextTurn("done")
            .build();
    var defaultCompactor = SummarizingCompactor.newBuilder(model).build();
    var before = new CopyOnWriteArrayList<List<Message>>();
    var after = new CopyOnWriteArrayList<List<Message>>();
    ContextCompactor archiving =
        (history, context) -> {
          before.add(history);
          var result = defaultCompactor.compact(history, context);
          after.add(result.history());
          return result;
        };

    var events =
        runToSuccess(compactingAfterTheFirstToolResult(model).withContextCompactor(archiving));

    assertEquals(
        List.of(List.of(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL)),
        before.stream().map(history -> history.stream().map(Message::role).toList()).toList());
    var compacted =
        List.of(
            Message.system("sys"), Message.user(SummarizingCompactor.SUMMARY_PREFIX + "the gist"));
    assertEquals(List.of(compacted), after);
    var edited = events.eventsOf(QueryEvent.ContextEdited.class);
    assertEquals(1, edited.size());
    assertEquals(2, edited.getFirst().removedBlocks());
    assertEquals(96L, edited.getFirst().tokensBefore());
    assertEquals(10L, edited.getFirst().tokensAfter());
  }

  @Test
  void aDisabledCompactorWarnsOnceAndKeepsTheWholeHistory() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(new ToolCall("c1", "echo", Map.of()))
            .withToolCallsTurn(new ToolCall("c2", "echo", Map.of()))
            .withTextTurn("done")
            .build();

    var events =
        runToSuccess(
            compactingAfterTheFirstToolResult(model)
                .withContextCompactor(ContextCompactor.disabled()));

    assertEquals(
        List.of(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT, Role.TOOL),
        model.calls().getLast().stream().map(Message::role).toList());
    assertEquals(1, events.eventsOf(QueryEvent.ContextWarning.class).size());
    assertEquals(List.of(), events.eventsOf(QueryEvent.ContextEdited.class));
  }

  @Test
  void aCustomCompactorReceivesTheSessionsIdAndCurrentTurnIndex() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(new ToolCall("c1", "echo", Map.of()))
            .withTextTurn("done")
            .build();
    var contexts = new CopyOnWriteArrayList<HookContext>();
    ContextCompactor observing =
        (history, context) -> {
          contexts.add(context);
          return CompactionResult.noOp(history);
        };

    runToSuccess(
        compactingAfterTheFirstToolResult(model)
            .withSessionId("compacting")
            .withContextCompactor(observing));

    assertEquals(
        List.of("compacting:1", "compacting:2", "compacting:2"),
        contexts.stream().map(c -> c.sessionId() + ":" + c.turnIndex()).toList());
    assertSame(model, contexts.getFirst().model());
  }

  /** A session whose history crosses the compaction watermark once it holds a tool result. */
  private static SessionOptions.Builder compactingAfterTheFirstToolResult(ScriptedModel model) {
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSystemPrompt("sys")
        .withTools(List.of(echo()))
        .withLimits(SessionLimits.newBuilder().withMaxContextTokens(100L).build())
        .withTokenCounter(
            messages -> messages.stream().anyMatch(m -> m.role() == Role.TOOL) ? 96L : 10L);
  }

  private static EventCollector runToSuccess(SessionOptions.Builder options) {
    var events = new EventCollector();
    try (var session = AgentSession.open(options.build())) {
      events.read(session.events());
      var future = session.send(UserMessage.text("go"));

      assertInstanceOf(ResultMessage.Success.class, Await.value("the session's result", future));
    }
    events.awaitDone();
    return events;
  }

  private static ToolBinding echo() {
    var tool =
        Tool.newBuilder()
            .withName("echo")
            .withDescription("answers ok")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    return ToolBinding.newBuilder(tool).withCategory(ToolCategory.READ).build();
  }
}
