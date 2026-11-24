/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ScriptedToolTest {

  private static final ToolContext CONTEXT = ToolContext.noop();

  private static ToolResult call(ScriptedTool scripted, Map<String, Object> arguments) {
    return scripted.tool().execute(arguments, CONTEXT);
  }

  @Test
  void outcomesAnswerCallsInTheOrderTheyWereScripted() {
    var given = ToolResult.successWithAttachments("given", List.of());
    var scripted =
        ScriptedTool.newBuilder("search")
            .withResult(given)
            .withSuccess("found")
            .withFailure("no index")
            .build();

    assertSame(given, call(scripted, Map.of()));
    assertEquals(ToolResult.success("found"), call(scripted, Map.of()));
    assertEquals(ToolResult.failure("no index"), call(scripted, Map.of()));
  }

  @Test
  void aScriptedThrowIsAnsweredAsAToolThatThrew() {
    var thrown = new IllegalArgumentException("bad query");
    var scripted = ScriptedTool.newBuilder("search").withThrow(thrown).withThrow(thrown).build();

    var result = call(scripted, Map.of());
    var direct =
        assertThrows(
            IllegalArgumentException.class,
            () -> scripted.tool().executor().execute(Map.of(), CONTEXT));

    assertEquals(new ToolOutcome.Threw("java.lang.IllegalArgumentException"), result.outcome());
    assertEquals("tool 'search' threw: bad query", result.output());
    assertSame(thrown, direct);
  }

  @Test
  void aCallPastTheScriptFailsNamingTheToolAndTheCallAndIsRecorded() {
    var scripted = ScriptedTool.newBuilder("search").withSuccess("once").build();
    call(scripted, Map.of("q", "a"));

    var exhausted =
        assertThrows(
            IllegalStateException.class,
            () -> scripted.tool().executor().execute(Map.of("q", "b"), CONTEXT));
    var answered = call(scripted, Map.of("q", "c"));

    assertEquals(
        "Scripted tool 'search' exhausted: 1 outcome(s) scripted, call 2 requested",
        exhausted.getMessage());
    assertEquals(new ToolOutcome.Threw("java.lang.IllegalStateException"), answered.outcome());
    assertEquals(
        "tool 'search' threw: Scripted tool 'search' exhausted: 1 outcome(s) scripted, call 3"
            + " requested",
        answered.output());
    assertEquals(List.of(Map.of("q", "a"), Map.of("q", "b"), Map.of("q", "c")), scripted.calls());
  }

  @Test
  void anEmptyScriptFailsItsFirstCall() {
    var scripted = ScriptedTool.newBuilder("search").build();

    var exhausted =
        assertThrows(
            IllegalStateException.class,
            () -> scripted.tool().executor().execute(Map.of(), CONTEXT));

    assertEquals(
        "Scripted tool 'search' exhausted: 0 outcome(s) scripted, call 1 requested",
        exhausted.getMessage());
  }

  @Test
  void callsAreRecordedInArrivalOrderAsCopiesThatKeepKeyOrderAndNullValues() {
    var scripted = ScriptedTool.newBuilder("search").withSuccess("a").withSuccess("b").build();
    var first = new LinkedHashMap<String, Object>();
    first.put("z", 1);
    first.put("a", null);
    call(scripted, first);
    first.put("added", "later");
    call(scripted, Map.of("q", "second"));

    var calls = scripted.calls();

    assertEquals(2, calls.size());
    assertEquals(List.of("z", "a"), List.copyOf(calls.getFirst().keySet()));
    assertEquals(Arrays.asList(1, null), new ArrayList<>(calls.getFirst().values()));
    assertEquals(Map.of("q", "second"), calls.get(1));
  }

  @Test
  void callsIsAnUnmodifiableListOfUnmodifiableMaps() {
    var scripted = ScriptedTool.newBuilder("search").withSuccess("a").build();
    call(scripted, new HashMap<>(Map.of("q", "x")));
    var calls = scripted.calls();
    var first = calls.getFirst();

    assertThrows(UnsupportedOperationException.class, () -> calls.add(Map.of()));
    assertThrows(UnsupportedOperationException.class, () -> first.put("q", "y"));
  }

  @Test
  void everyToolItReturnsSharesOneScriptAndOneRecord() {
    var scripted = ScriptedTool.newBuilder("search").withSuccess("a").withSuccess("b").build();

    var first = scripted.tool().execute(Map.of("n", 1), CONTEXT);
    var second = scripted.tool().execute(Map.of("n", 2), CONTEXT);

    assertSame(scripted.tool(), scripted.tool());
    assertEquals(List.of("a", "b"), List.of(first.output(), second.output()));
    assertEquals(List.of(Map.of("n", 1), Map.of("n", 2)), scripted.calls());
  }

  @Test
  void theToolLooksLikeTheOneItStandsInFor() {
    var city = ToolParameter.newBuilder().withName("city").withType(ParameterType.STRING).build();
    var units = ToolParameter.newBuilder().withName("units").withType(ParameterType.STRING).build();
    var days = ToolParameter.newBuilder().withName("days").withType(ParameterType.INTEGER).build();

    var tool =
        ScriptedTool.newBuilder("get_weather")
            .withDescription("Current weather for a city")
            .withParameter(city)
            .withParameters(List.of(units, days))
            .build()
            .tool();

    assertEquals("get_weather", tool.name());
    assertEquals("Current weather for a city", tool.description());
    assertEquals(List.of(city, units, days), tool.parameters());
  }

  @Test
  void byDefaultTheToolSaysItIsScriptedAndTakesNoParameters() {
    var tool = ScriptedTool.newBuilder("search").build().tool();

    assertEquals(
        "A scripted test tool: each call returns the next outcome its test scripted.",
        tool.description());
    assertEquals(List.of(), tool.parameters());
  }

  @Test
  void whatIsScriptedAfterBuildDoesNotReachTheBuiltTool() {
    var builder = ScriptedTool.newBuilder("search").withSuccess("a");
    var scripted = builder.build();
    builder
        .withSuccess("b")
        .withParameter(
            ToolParameter.newBuilder().withName("q").withType(ParameterType.STRING).build());

    call(scripted, Map.of());

    assertEquals(List.of(), scripted.tool().parameters());
    assertThrows(
        IllegalStateException.class, () -> scripted.tool().executor().execute(Map.of(), CONTEXT));
  }

  @Test
  void concurrentCallsConsumeEachOutcomeOnceAndAreEachRecordedOnce() {
    var count = 64;
    var builder = ScriptedTool.newBuilder("search");
    IntStream.range(0, count).forEach(i -> builder.withSuccess("r" + i));
    var scripted = builder.build();
    var start = new CountDownLatch(1);
    var futures = new ArrayList<Future<ToolResult>>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var i = 0; i < count; i++) {
        var arguments = Map.<String, Object>of("n", i);
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return call(scripted, arguments);
                }));
      }
      start.countDown();
      var outputs = new ArrayList<String>();
      for (var future : futures) {
        outputs.add(Await.value("a scripted call", future).output());
      }

      assertEquals(sortedRange(count, i -> "r" + i), outputs.stream().sorted().toList());
      assertEquals(
          sortedRange(count, i -> "n" + i),
          scripted.calls().stream().map(call -> "n" + call.get("n")).sorted().toList());
    }
  }

  private static List<String> sortedRange(int count, Function<Integer, String> label) {
    return IntStream.range(0, count).boxed().map(label).sorted().toList();
  }

  @Test
  void aNullOrBlankNameIsRejectedAsToolBuilderRejectsIt() {
    assertRejected(IllegalStateException.class, "Tool name is required", () -> builder(null));
    assertRejected(IllegalStateException.class, "Tool name is required", () -> builder(""));
    assertRejected(IllegalStateException.class, "Tool name is required", () -> builder(" \t"));
  }

  @Test
  void eachNullArgumentIsRejectedWithItsName() {
    var builder = ScriptedTool.newBuilder("search");
    var withNull = Arrays.asList((ToolParameter) null);

    assertRejectsNull("description must not be null", () -> builder.withDescription(null));
    assertRejectsNull("parameter must not be null", () -> builder.withParameter(null));
    assertRejectsNull("parameters must not be null", () -> builder.withParameters(null));
    assertRejectsNull("parameter must not be null", () -> builder.withParameters(withNull));
    assertRejectsNull("result must not be null", () -> builder.withResult(null));
    assertRejectsNull("output must not be null", () -> builder.withSuccess(null));
    assertRejectsNull("error must not be null", () -> builder.withFailure(null));
    assertRejectsNull("exception must not be null", () -> builder.withThrow(null));
  }

  private static void builder(String name) {
    ScriptedTool.newBuilder(name);
  }

  private static void assertRejectsNull(String message, Executable executable) {
    assertRejected(NullPointerException.class, message, executable);
  }

  private static void assertRejected(
      Class<? extends RuntimeException> type, String message, Executable executable) {
    assertEquals(message, assertThrows(type, executable).getMessage());
  }
}
