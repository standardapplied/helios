/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostCalculator.Pricing;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A {@link Subagent} is named, described and composed from its own options, and a session's options
 * refuse subagents the {@code Agent} tool could not run as documented.
 */
final class SubagentTest {

  private static final CostCalculator COSTS =
      CostCalculator.staticTable(Map.of("scripted", new Pricing(1, 1)));

  private static SessionOptions.Builder options() {
    return SessionOptions.newBuilder().withModel(ScriptedModel.newBuilder().build());
  }

  private static Subagent subagent(String name) {
    return new Subagent(name, "does " + name, options().build());
  }

  private static SessionLimits budget() {
    return SessionLimits.newBuilder().withMaxBudgetMicroUsd(1_000).build();
  }

  @ParameterizedTest
  @ValueSource(strings = {"a", "matching", "web_search-2", "z0"})
  void aNameOfALowercaseLetterThenLettersDigitsUnderscoresOrHyphensIsAccepted(String name) {
    assertEquals(name, subagent(name).name());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "Matching", "2fast", "-a", "web search", "a.b"})
  void aNameBreakingTheRuleIsRefused(String name) {
    var refused = assertThrows(IllegalArgumentException.class, () -> subagent(name));
    assertEquals(
        "subagent name \"" + name + "\" must match [a-z][a-z0-9_-]{0,63}", refused.getMessage());
  }

  @Test
  void aNameIsAtMost64Characters() {
    var longest = "a".repeat(64);
    assertEquals(longest, subagent(longest).name());
    assertThrows(IllegalArgumentException.class, () -> subagent(longest + "a"));
  }

  @Test
  void aBlankDescriptionAndAMissingPartAreRefused() {
    var built = options().build();
    assertThrows(IllegalArgumentException.class, () -> new Subagent("a", " ", built));
    assertThrows(NullPointerException.class, () -> new Subagent(null, "d", built));
    assertThrows(NullPointerException.class, () -> new Subagent("a", null, built));
    assertThrows(NullPointerException.class, () -> new Subagent("a", "d", null));
  }

  @Test
  void optionsThatRegisterAskUserQuestionAreRefused() {
    var asking = options().withAskUserQuestion().build();
    var refused =
        assertThrows(IllegalArgumentException.class, () -> new Subagent("a", "d", asking));
    assertEquals(
        "subagent \"a\" registers AskUserQuestion, but a subagent has no one to ask",
        refused.getMessage());
  }

  @Test
  void subagentsDefaultToNoneAppendReplaceAndSurviveToBuilder() {
    assertEquals(List.of(), options().build().subagents());

    var a = subagent("a");
    var b = subagent("b");
    var c = subagent("c");
    var built = options().withSubagent(a).withSubagent(b).build();
    assertEquals(List.of(a, b), built.subagents());
    assertEquals(List.of(a, b), built.toBuilder().build().subagents());
    assertEquals(List.of(c), built.toBuilder().withSubagents(List.of(c)).build().subagents());
    assertThrows(NullPointerException.class, () -> options().withSubagent(null));
    var withNull = Arrays.asList(a, null);
    assertThrows(NullPointerException.class, () -> options().withSubagents(withNull));
  }

  @Test
  void twoSubagentsWithOneNameAreRefused() {
    var builder = options().withSubagent(subagent("a")).withSubagent(subagent("a"));
    var refused = assertThrows(IllegalArgumentException.class, builder::build);
    assertEquals("duplicate subagent name: a", refused.getMessage());
  }

  @Test
  void aToolNamedAgentIsRefusedOnlyBesideSubagents() {
    var agent = EndingSessions.binding("Agent", (arguments, context) -> ToolResult.success(""));
    assertEquals(1, options().withTool(agent).build().tools().size());

    var builder = options().withTool(agent).withSubagent(subagent("a"));
    var refused = assertThrows(IllegalArgumentException.class, builder::build);
    assertEquals(
        "a tool named Agent collides with the built-in tool subagents add", refused.getMessage());
  }

  @Test
  void underABudgetASubagentWithoutACostCalculatorIsRefusedByName() {
    var builder =
        options().withLimits(budget()).withCostCalculator(COSTS).withSubagent(subagent("free"));
    var refused = assertThrows(IllegalArgumentException.class, builder::build);
    assertTrue(refused.getMessage().startsWith("subagent \"free\" has no cost calculator"));

    var priced = new Subagent("priced", "d", options().withCostCalculator(COSTS).build());
    options().withLimits(budget()).withCostCalculator(COSTS).withSubagent(priced).build();
    options().withSubagent(subagent("free")).build();
  }

  @Test
  void underABudgetASubagentsOwnSubagentWithoutACostCalculatorIsRefusedByName() {
    var outer =
        new Subagent(
            "outer",
            "d",
            options().withCostCalculator(COSTS).withSubagent(subagent("inner")).build());
    var builder = options().withLimits(budget()).withCostCalculator(COSTS).withSubagent(outer);

    var refused = assertThrows(IllegalArgumentException.class, builder::build);

    assertTrue(refused.getMessage().startsWith("subagent \"inner\" has no cost calculator"));
  }

  @Test
  void onlyAnAgentCallNamingASubagentRunsUnderThatSubagentsLimits() {
    var limits = SessionLimits.newBuilder().withMaxWallClock(Duration.ofMinutes(7)).build();
    var matching = new Subagent("matching", "finds a match", options().withLimits(limits).build());
    var tool = new AgentTool(List.of(matching), SessionLimits.defaults());

    assertEquals(limits, tool.childLimits(call("Agent", "matching")).orElseThrow());
    assertTrue(tool.childLimits(call("Agent", "nobody")).isEmpty());
    assertTrue(tool.childLimits(call("lookup", "matching")).isEmpty());
  }

  private static ToolCall call(String tool, String agent) {
    return new ToolCall("c-1", tool, Map.of("agent", agent, "prompt", "p"));
  }
}
