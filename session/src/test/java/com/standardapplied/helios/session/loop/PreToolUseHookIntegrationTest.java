/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static com.standardapplied.helios.session.test.HookInputs.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** {@link PreToolUseHook} outcomes, and the hook name they carry, wired through the loop. */
final class PreToolUseHookIntegrationTest {

  private static final PreToolUseHook MUTATES_TO_A =
      named("A", (call, ctx) -> HookOutcome.mutateArgs(Map.of("v", "a")));

  private final HookLoop loop = new HookLoop();
  private final List<Map<String, Object>> executed = new CopyOnWriteArrayList<>();

  private ResultMessage callEchoThenAnswer(
      Map<String, Object> arguments, String answer, PreToolUseHook hook) {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(HookLoop.echoCall(arguments))
            .withTextTurn(answer)
            .build();
    return loop.run(model, HookLoop.echoTool(), UserMessage.text("call echo"), hook);
  }

  @Test
  void preToolUseBlockEmitsToolBlockedAndSubstitutesFailureResult() {
    PreToolUseHook blocker = (call, ctx) -> HookOutcome.block("not allowed");
    var result = callEchoThenAnswer(Map.of("v", "x"), "after-block", blocker);
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("after-block", success.result());
    var blocked = loop.first(QueryEvent.ToolBlocked.class);
    assertEquals("not allowed", blocked.reason());
    var toolResult = loop.first(QueryEvent.ToolResult.class);
    assertEquals(
        new ToolResult(
            new ToolOutcome.Rejected(blocked.hookName(), "not allowed"),
            "blocked by " + blocked.hookName() + ": not allowed",
            List.of()),
        toolResult.result());
  }

  @Test
  void preToolUseMutateRewritesArgsAndEmitsToolMutated() {
    PreToolUseHook rewriter = (call, ctx) -> HookOutcome.mutateArgs(Map.of("v", "mutated"));
    var result = callEchoThenAnswer(Map.of("v", "original"), "done", rewriter);
    assertInstanceOf(ResultMessage.Success.class, result);
    var mutated = loop.first(QueryEvent.ToolMutated.class);
    assertEquals("original", mutated.inputBefore().get("v"));
    assertEquals("mutated", mutated.inputAfter().get("v"));
    var toolResult = loop.first(QueryEvent.ToolResult.class);
    assertEquals("echoed: mutated", toolResult.result().output());
  }

  @Test
  void preToolUseMutateKeepsAnUntouchedJsonNullArgument() {
    PreToolUseHook rewriter =
        (call, ctx) -> {
          var args = new LinkedHashMap<>(call.arguments());
          args.put("v", "mutated");
          return HookOutcome.mutateArgs(args);
        };
    var arguments = new LinkedHashMap<String, Object>();
    arguments.put("v", "original");
    arguments.put("cursor", null);

    var result = callEchoThenAnswer(arguments, "done", rewriter);

    assertInstanceOf(ResultMessage.Success.class, result);
    var mutated = loop.first(QueryEvent.ToolMutated.class);
    assertEquals("mutated", mutated.inputAfter().get("v"));
    assertTrue(mutated.inputAfter().containsKey("cursor"));
    assertNull(mutated.inputAfter().get("cursor"));
    var dispatched = loop.first(QueryEvent.ToolUse.class).call().arguments();
    assertTrue(dispatched.containsKey("cursor"));
    assertNull(dispatched.get("cursor"));
    assertEquals("echoed: mutated", loop.first(QueryEvent.ToolResult.class).result().output());
  }

  @Test
  void preToolUseStopTerminates() {
    PreToolUseHook stopper = (call, ctx) -> HookOutcome.stop("pre-tool-stop");
    var model = ScriptedModel.newBuilder().withToolCallsTurn(HookLoop.echoCall(Map.of())).build();
    var result = loop.run(model, HookLoop.echoTool(), UserMessage.text("call echo"), stopper);
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("pre-tool-stop", success.result());
  }

  @Test
  void preToolUseInjectSubstitutesFailureAndQueuesMessage() {
    PreToolUseHook injector = (call, ctx) -> HookOutcome.inject("review args");
    var result = callEchoThenAnswer(Map.of(), "after-inject", injector);
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("after-inject", success.result());
    var toolResult = loop.first(QueryEvent.ToolResult.class);
    assertTrue(toolResult.result().output().contains("hook injected"));
  }

  @Test
  void hookFiredCarriesActualHookName() {
    var named = named("MySecurityGuard", (call, ctx) -> HookOutcome.block("nope"));
    callEchoThenAnswer(Map.of("v", "x"), "done", named);
    var hookFired = loop.first(QueryEvent.HookFired.class);
    assertEquals("MySecurityGuard", hookFired.hookName(), "hookName must carry the hook's name");
    assertEquals("PreToolUseHook", hookFired.phase(), "phase must carry the lifecycle phase");
    var blocked = loop.first(QueryEvent.ToolBlocked.class);
    assertEquals(
        "MySecurityGuard", blocked.hookName(), "ToolBlocked.hookName must carry the hook's name");
  }

  @Test
  void aMutationFromAnotherThreadOfTheMapAHookReturnedNeverReachesTheTool() {
    var days = new ArrayList<Object>(List.of(1, 2));
    var returned = new CountDownLatch(1);
    var mutated = new CountDownLatch(1);
    PreToolUseHook rewriter =
        (call, ctx) -> {
          var outcome = HookOutcome.mutateArgs(Map.of("v", "mutated", "days", days));
          returned.countDown();
          Await.latch("mutation", mutated);
          return outcome;
        };
    var mutator = mutateAfter(returned, () -> days.add("added"), mutated);
    var executed = new AtomicReference<Map<String, Object>>();
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(HookLoop.echoCall(Map.of("v", "original")))
            .withTextTurn("done")
            .build();

    var result =
        loop.run(model, awaitingTool(mutated, executed), UserMessage.text("call"), rewriter);

    Await.termination("mutator", mutator);
    assertInstanceOf(ResultMessage.Success.class, result);
    var expected = Map.<String, Object>of("v", "mutated", "days", List.of(1, 2));
    assertEquals(expected, executed.get());
    assertEquals(expected, loop.first(QueryEvent.ToolMutated.class).inputAfter());
    assertEquals(expected, loop.first(QueryEvent.ToolUse.class).call().arguments());
  }

  @Test
  void aMutationFromAnotherThreadOfTheMapAModelCallWasBuiltFromNeverReachesTheTool() {
    var options = new HashMap<String, Object>(Map.of("lang", "en"));
    var arguments = new HashMap<String, Object>(Map.of("options", options));
    var call = HookLoop.echoCall(arguments);
    var observed = new CountDownLatch(1);
    var mutated = new CountDownLatch(1);
    var mutator = mutateAfter(observed, () -> options.put("added", true), mutated);
    PreToolUseHook observer =
        (seen, ctx) -> {
          observed.countDown();
          Await.latch("mutation", mutated);
          return HookOutcome.cont();
        };
    var executed = new AtomicReference<Map<String, Object>>();
    var model = ScriptedModel.newBuilder().withToolCallsTurn(call).withTextTurn("done").build();

    var result =
        loop.run(model, awaitingTool(mutated, executed), UserMessage.text("call"), observer);

    Await.termination("mutator", mutator);
    assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals(Map.of("options", Map.of("lang", "en")), executed.get());
    assertEquals(executed.get(), loop.first(QueryEvent.ToolUse.class).call().arguments());
  }

  @Test
  void aHookReturningArgumentsThatAreNotJsonRefusesTheCallAndTheToolNeverRuns() {
    var cyclic = new HashMap<String, Object>();
    cyclic.put("self", cyclic);
    PreToolUseHook broken = (call, ctx) -> HookOutcome.mutateArgs(cyclic);
    var executions = new AtomicInteger();
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(HookLoop.echoCall(Map.of("v", "original")))
            .withTextTurn("done")
            .build();

    loop.run(
        model,
        HookLoop.echoTool(
            (args, ctx) -> {
              executions.incrementAndGet();
              return ToolResult.success("ran");
            }),
        UserMessage.text("call echo"),
        broken);

    var by = loop.first(QueryEvent.ToolBlocked.class).hookName();
    assertEquals(
        new ToolOutcome.Rejected(
            by, "hook threw java.lang.IllegalArgumentException: args.self contains itself"),
        loop.first(QueryEvent.ToolResult.class).result().outcome());
    assertEquals(0, executions.get());
  }

  // ── the chain ────────────────────────────────────────────────────────────

  private static PreToolUseHook recording(
      String name, List<String> seen, HookOutcome.PreToolUse outcome) {
    return named(
        name,
        (call, ctx) -> {
          seen.add(name + ":" + call.arguments().get("v"));
          return outcome;
        });
  }

  private void runChain(PreToolUseHook... hooks) {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(HookLoop.echoCall(Map.of("v", "o")))
            .withTextTurn("done")
            .build();
    loop.run(
        model,
        HookLoop.echoTool(
            (args, ctx) -> {
              executed.add(args);
              return ToolResult.success("ran");
            }),
        UserMessage.text("call echo"),
        hooks);
  }

  @Test
  void eachHookSeesTheCallTheHooksBeforeItLeftAndTheToolRunsTheLast() {
    var seen = new ArrayList<String>();

    runChain(
        MUTATES_TO_A,
        recording("B", seen, HookOutcome.mutateArgs(Map.of("v", "b"))),
        recording("C", seen, HookOutcome.cont()));

    assertEquals(List.of("B:a", "C:b"), seen);
    assertEquals(List.of(Map.<String, Object>of("v", "b")), executed);
    assertEquals(
        List.of("ToolMutated A o -> a", "ToolMutated B a -> b", "ToolUse b"),
        loop.eventsOf(QueryEvent.class).stream()
            .<String>mapMulti(
                (event, out) -> {
                  switch (event) {
                    case QueryEvent.ToolMutated m ->
                        out.accept(
                            "ToolMutated "
                                + m.hookName()
                                + " "
                                + m.inputBefore().get("v")
                                + " -> "
                                + m.inputAfter().get("v"));
                    case QueryEvent.ToolUse u ->
                        out.accept("ToolUse " + u.call().arguments().get("v"));
                    default -> {}
                  }
                })
            .toList());
  }

  @Test
  void aBlockAfterAMutationRefusesTheMutatedCall() {
    var seen = new ArrayList<String>();

    runChain(MUTATES_TO_A, recording("B", seen, HookOutcome.block("no")));

    var mutated = HookLoop.echoCall(Map.of("v", "a"));
    assertEquals(List.of("B:a"), seen);
    assertEquals(mutated, loop.first(QueryEvent.ToolBlocked.class).call());
    assertEquals(
        new ToolOutcome.Rejected("B", "no"),
        loop.first(QueryEvent.ToolResult.class).result().outcome());
    assertEquals(mutated, loop.first(QueryEvent.ToolResult.class).call());
    assertEquals(List.of(), executed);
  }

  @Test
  void anInjectAfterAMutationEndsTheChain() {
    var seen = new ArrayList<String>();

    runChain(
        MUTATES_TO_A,
        named("B", (call, ctx) -> HookOutcome.inject("look again")),
        recording("C", seen, HookOutcome.cont()));

    assertEquals(List.of(), seen);
    assertEquals(
        HookLoop.echoCall(Map.of("v", "a")), loop.first(QueryEvent.ToolResult.class).call());
  }

  @Test
  void aStopAfterAMutationEndsTheChain() {
    var seen = new ArrayList<String>();

    runChain(
        MUTATES_TO_A,
        named("B", (call, ctx) -> HookOutcome.stop("halt")),
        recording("C", seen, HookOutcome.cont()));

    assertEquals(List.of(), seen);
  }

  /**
   * A thread that waits for {@code ready}, then runs {@code mutation} and counts down {@code done}.
   */
  private static Thread mutateAfter(CountDownLatch ready, Runnable mutation, CountDownLatch done) {
    return Thread.ofVirtual()
        .start(
            () -> {
              Await.latch("ready to mutate", ready);
              mutation.run();
              done.countDown();
            });
  }

  /** A registry holding {@code echo}, which waits for {@code mutated} and records its arguments. */
  private static ToolRegistry awaitingTool(
      CountDownLatch mutated, AtomicReference<Map<String, Object>> executed) {
    return HookLoop.echoTool(
        (args, ctx) -> {
          Await.latch("mutation", mutated);
          executed.set(args);
          return ToolResult.success("done");
        });
  }
}
