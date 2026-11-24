/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.testing.ScriptedModel;
import com.standardapplied.helios.testing.ScriptedTool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A {@link ScriptedTool} in a real session, driven by a {@link ScriptedModel}. */
final class ScriptedToolSessionTest {

  @Test
  void aSessionReceivesEachScriptedOutcomeInOrderAndTheToolRecordsEachCall() {
    var search =
        ScriptedTool.newBuilder("search").withSuccess("3 hits").withFailure("no index").build();
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(new ToolCall("c1", "search", Map.of("q", "helios")))
            .withToolCallsTurn(new ToolCall("c2", "search", Map.of("q", "sessions")))
            .withTextTurn("done")
            .build();
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withTool(
                ToolBinding.newBuilder(search.tool()).withCategory(ToolCategory.READ).build());

    var run = WriteToolSessions.run(model, options);

    assertEquals(List.of(Map.of("q", "helios"), Map.of("q", "sessions")), search.calls());
    assertEquals(
        List.of(ToolResult.success("3 hits"), ToolResult.failure("no index")),
        run.events().eventsOf(QueryEvent.ToolResult.class).stream()
            .map(QueryEvent.ToolResult::result)
            .toList());
    assertEquals(
        List.of("c1", "c2"),
        run.events().eventsOf(QueryEvent.ToolResult.class).stream()
            .map(result -> result.call().id())
            .toList());
    assertInstanceOf(ResultMessage.Success.class, run.terminal());
  }
}
