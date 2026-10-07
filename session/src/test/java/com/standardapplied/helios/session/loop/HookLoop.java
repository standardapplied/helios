/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.ContextCompactor;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.hooks.HookRegistry;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Runs an {@link AgentLoop} to its terminal with the hooks a hook integration test registers, then
 * exposes the events it emitted and the history it built. Closed after each test.
 */
final class HookLoop implements AutoCloseable {

  private final LoopFixture fixture =
      new LoopFixture("sess-hook", Instant.parse("2026-05-15T09:00:00Z"));
  private final SessionState state = fixture.state();

  /** A registry holding one READ tool, {@code echo}, that answers {@code "echoed: " + v}. */
  static ToolRegistry echoTool() {
    var echo =
        Tool.newBuilder()
            .withName("echo")
            .withDescription("echo")
            .withExecutor((args, ctx) -> ToolResult.success("echoed: " + args.get("v")))
            .build();
    return new ToolRegistry(
        List.of(ToolBinding.newBuilder(echo).withCategory(ToolCategory.READ).build()));
  }

  /** A call of the {@code echo} tool with id {@code c1}. */
  static ToolCall echoCall(Map<String, Object> arguments) {
    return new ToolCall("c1", "echo", arguments);
  }

  /** Runs the loop over {@code message} with {@code hooks} and no tools. */
  ResultMessage run(Model model, UserMessage message, Hook... hooks) {
    return run(model, ToolRegistry.empty(), message, hooks);
  }

  /** Runs the loop over {@code message} with {@code hooks} and {@code tools}. */
  ResultMessage run(Model model, ToolRegistry tools, UserMessage message, Hook... hooks) {
    var queue = new SteeringQueue(8);
    queue.offer(message);
    var collaborators =
        fixture.collaborators(new HookRegistry(List.of(hooks)), fixture.dispatch(tools), queue);
    return fixture
        .loop(collaborators, model, TokenCounter.charBased(), ContextCompactor.disabled())
        .run(state, SessionLimits.defaults());
  }

  List<Message> history() {
    return state.history().snapshot();
  }

  /** The emitted events of type {@code type}, in emission order. */
  <E extends QueryEvent> List<E> eventsOf(Class<E> type) {
    return fixture.events.stream().filter(type::isInstance).map(type::cast).toList();
  }

  /** The first emitted event of type {@code type}; fails when there is none. */
  <E extends QueryEvent> E first(Class<E> type) {
    return eventsOf(type).stream().findFirst().orElseThrow();
  }

  /** The first message of the built history with role {@code TOOL}; fails when there is none. */
  Message toolMessage() {
    return history().stream().filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
  }

  @Override
  public void close() {
    fixture.close();
  }
}
