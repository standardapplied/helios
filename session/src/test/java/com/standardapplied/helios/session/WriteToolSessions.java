/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Sessions over one {@code WRITE} tool, {@code Write}, keyed by its {@code path} argument, that
 * records the arguments of every call it executes in {@link #executed}.
 */
final class WriteToolSessions {

  /** Every call the {@code Write} tool executed, by its arguments. */
  final List<Map<String, Object>> executed = new CopyOnWriteArrayList<>();

  /** What a run emitted, the model it ran on, and how it ended. */
  record Run(EventCollector events, ScriptedModel model, ResultMessage terminal) {

    ToolResult onlyResult() {
      var results = events.eventsOf(QueryEvent.ToolResult.class);
      assertEquals(1, results.size(), "tool results");
      return results.getFirst().result();
    }

    QueryEvent.ToolBlocked onlyBlock() {
      var blocks = events.eventsOf(QueryEvent.ToolBlocked.class);
      assertEquals(1, blocks.size(), "blocks");
      return blocks.getFirst();
    }
  }

  ToolBinding writeTool() {
    return writeTool(args -> new ToolPermissionKey("Write", (String) args.get("path")));
  }

  ToolBinding writeTool(Function<Map<String, Object>, ToolPermissionKey> key) {
    var tool =
        Tool.newBuilder()
            .withName("Write")
            .withDescription("write")
            .withExecutor(
                (args, ctx) -> {
                  executed.add(args);
                  return ToolResult.success("wrote");
                })
            .build();
    return ToolBinding.newBuilder(tool)
        .withCategory(ToolCategory.WRITE)
        .withPermissionKeyExtractor(key)
        .build();
  }

  /** Options of a session holding {@link #writeTool()} under {@code permission}. */
  SessionOptions.Builder options(Permission permission) {
    return SessionOptions.newBuilder().withTools(List.of(writeTool())).withPermission(permission);
  }

  static ToolCall write(String path) {
    return new ToolCall("c1", "Write", Map.of("path", path));
  }

  /** Runs a session that makes {@code calls} in one turn, then answers {@code done}. */
  static Run run(SessionOptions.Builder options, ToolCall... calls) {
    return run(options, session -> event -> {}, calls);
  }

  /** As {@link #run(SessionOptions.Builder, ToolCall...)}, reacting to each event it emits. */
  static Run run(
      SessionOptions.Builder options,
      Function<AgentSession, Consumer<QueryEvent>> reactions,
      ToolCall... calls) {
    var model = answering(calls);
    return run(model, options.withModel(model), reactions);
  }

  /** Runs a session of {@code options}, which already hold {@code model}. */
  static Run run(ScriptedModel model, SessionOptions.Builder options) {
    return run(model, options, session -> event -> {});
  }

  /** As {@link #run(ScriptedModel, SessionOptions.Builder)}, reacting to each event it emits. */
  static Run run(
      ScriptedModel model,
      SessionOptions.Builder options,
      Function<AgentSession, Consumer<QueryEvent>> reactions) {
    try (var session = AgentSession.open(options.build())) {
      var events = new EventCollector(reactions.apply(session));
      events.read(session.events());
      var future = session.send(UserMessage.text("go"));
      var terminal = Await.value("the session to end", future);
      events.awaitRunEnded();
      return new Run(events, model, terminal);
    }
  }

  /** A model that makes {@code calls} in one turn, then answers {@code done}. */
  static ScriptedModel answering(ToolCall... calls) {
    return ScriptedModel.newBuilder().withToolCallsTurn(calls).withTextTurn("done").build();
  }
}
