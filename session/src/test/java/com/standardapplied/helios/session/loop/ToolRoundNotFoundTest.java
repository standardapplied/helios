/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolVisibilityContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * A call naming no registered tool, run through a {@link ToolRound}: its result names the closest
 * tools offered on the turn, and a found tool's call runs no visibility predicate.
 */
final class ToolRoundNotFoundTest {

  private static final String SID = "sess-round";

  private final LoopFixture fixture = new LoopFixture(SID, Instant.parse("2026-11-24T08:00:00Z"));
  private final SteeringQueue queue = new SteeringQueue(8);

  /** Every visibility context a binding's predicate was asked about, in order. */
  private final List<ToolVisibilityContext> asked = new CopyOnWriteArrayList<>();

  private ToolBinding tool(String name, ToolResult answer, Predicate<ToolVisibilityContext> shown) {
    var tool =
        Tool.newBuilder()
            .withName(name)
            .withDescription(name)
            .withExecutor((args, ctx) -> answer)
            .build();
    return ToolBinding.newBuilder(tool)
        .withCategory(ToolCategory.READ)
        .withVisibility(
            ctx -> {
              asked.add(ctx);
              return shown.test(ctx);
            })
        .build();
  }

  private ToolBinding shown(String name) {
    return tool(name, ToolResult.success(name), ctx -> true);
  }

  /** Runs {@code calls} as one round of the run's first turn; returns the state it ran on. */
  private SessionState round(ToolRegistry tools, SecretRegistry secrets, ToolCall... calls) {
    var collaborators =
        fixture.collaborators(HookRegistry.empty(), fixture.dispatch(tools, secrets), queue);
    var emitter = collaborators.emitter();
    var state = fixture.state();
    state.beginTurn();
    new ToolRound(collaborators, emitter, new HookEffects(emitter, queue))
        .run(state, List.of(calls), SessionLimits.defaults());
    return state;
  }

  private SessionState round(ToolRegistry tools, ToolCall... calls) {
    return round(tools, new SecretRegistry(), calls);
  }

  private List<ToolResult> results() {
    return fixture.events.stream()
        .filter(QueryEvent.ToolResult.class::isInstance)
        .map(event -> ((QueryEvent.ToolResult) event).result())
        .toList();
  }

  private static List<String> toolMessages(SessionState state) {
    return state.history().snapshot().stream()
        .filter(message -> message.role() == Role.TOOL)
        .map(Message::content)
        .toList();
  }

  private static ToolCall call(String name) {
    return new ToolCall("c-" + name, name, Map.of());
  }

  @Test
  void anUnknownCloseNameIsAnsweredWithTheSuggestion() {
    var state = round(new ToolRegistry(List.of(shown("Read"), shown("Write"))), call("raed"));

    assertEquals(List.of(ToolResult.notFound("raed", List.of("Read"))), results());
    assertEquals(List.of("tool not found: raed; did you mean: Read?"), toolMessages(state));
  }

  @Test
  void anUnknownDistantNameIsAnsweredWithTheBareText() {
    var state = round(new ToolRegistry(List.of(shown("Read"))), call("Bash"));

    assertEquals(List.of(ToolResult.notFound("Bash")), results());
    assertEquals(List.of("tool not found: Bash"), toolMessages(state));
  }

  @Test
  void aToolHiddenOnTheTurnIsNotSuggestedWhileAnOfferedOneIs() {
    var hidden = tool("Read", ToolResult.success("read"), ctx -> ctx.turnIndex() > 1);
    var tools = new ToolRegistry(List.of(hidden, shown("Ready")));

    round(tools, call("Rea"));

    assertEquals(List.of(ToolResult.notFound("Rea", List.of("Ready"))), results());
    assertEquals(
        List.of(new ToolVisibilityContext(SID, 1), new ToolVisibilityContext(SID, 1)), asked);
  }

  @Test
  void aFoundToolRunsNoVisibilityPredicateAndKeepsItsOwnNotFound() {
    var ownNotFound = ToolResult.notFound("page-7", List.of("page-1"));
    var tools = new ToolRegistry(List.of(shown("echo"), tool("lookup", ownNotFound, ctx -> true)));

    round(tools, call("echo"), call("lookup"));

    assertEquals(List.of(ToolResult.success("echo"), ownNotFound), results());
    assertEquals(List.of(), asked);
  }

  @Test
  void theSuggestionIsRedactedBeforeItIsEmittedAndKept() {
    var secrets = new SecretRegistry();
    secrets.register("TOKEN", "tok12345");
    var tools = new ToolRegistry(List.of(shown("fetch_tok12345")));

    var state = round(tools, secrets, call("fetch_tok1234"));

    var redacted = "tool not found: fetch_tok1234; did you mean: fetch_<redacted:TOKEN>?";
    assertEquals(List.of(redacted), results().stream().map(ToolResult::output).toList());
    assertEquals(List.of(redacted), toolMessages(state));
  }
}
