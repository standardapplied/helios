/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The session's default compactor through the loop: the history crosses the compaction watermark
 * after the first tool round, and the next model request carries no earlier assistant turn.
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
    var events = new CollectingSubscriber();
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSystemPrompt("sys")
            .withTools(new ToolRegistry(List.of(echo())))
            .withLimits(SessionLimits.newBuilder().withMaxContextTokens(100L).build())
            .withTokenCounter(
                messages -> messages.stream().anyMatch(m -> m.role() == Role.TOOL) ? 96L : 10L)
            .build();

    try (var session = AgentSession.create(options)) {
      session.events().subscribe(events);
      session.send(UserMessage.text("go"));

      assertInstanceOf(
          ResultMessage.Success.class, Await.value("the session's result", session.result()));
    }
    events.awaitDone();

    assertEquals(
        List.of(
            Message.system("sys"), Message.user(SummarizingCompactor.SUMMARY_PREFIX + "the gist")),
        model.calls().getLast());
    assertEquals(3, model.calls().size());
    assertEquals(1, events.eventsOf(QueryEvent.ContextEdited.class).size());
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
