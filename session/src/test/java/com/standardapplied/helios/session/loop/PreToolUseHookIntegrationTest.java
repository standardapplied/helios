/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link PreToolUseHook} outcomes, and the hook name they carry, wired through the loop. */
final class PreToolUseHookIntegrationTest {

  private final HookLoop loop = new HookLoop();

  @AfterEach
  void closeLoop() {
    loop.close();
  }

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
    var named =
        new PreToolUseHook() {
          @Override
          public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
            return HookOutcome.block("nope");
          }

          @Override
          public String name() {
            return "MySecurityGuard";
          }
        };
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
          return outcome;
        };
    var mutator = mutateAfter(returned, days, mutated);
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
    var mutator = mutateAfter(observed, options, mutated);
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
  void aHookReturningArgumentsThatAreNotJsonIsTreatedAsContinueAndTheToolNeverSeesThem() {
    var cyclic = new HashMap<String, Object>();
    cyclic.put("self", cyclic);
    PreToolUseHook broken = (call, ctx) -> HookOutcome.mutateArgs(cyclic);

    var result = callEchoThenAnswer(Map.of("v", "original"), "done", broken);

    assertInstanceOf(ResultMessage.Success.class, result);
    assertTrue(loop.eventsOf(QueryEvent.ToolMutated.class).isEmpty());
    assertEquals("echoed: original", loop.first(QueryEvent.ToolResult.class).result().output());
  }

  /**
   * A thread that waits for {@code ready}, then adds to {@code target} and counts down {@code
   * done}.
   */
  private static Thread mutateAfter(CountDownLatch ready, Object target, CountDownLatch done) {
    return Thread.ofVirtual()
        .start(
            () -> {
              Await.latch("ready to mutate", ready);
              switch (target) {
                case List<?> list -> addTo(list);
                case Map<?, ?> map -> putInto(map);
                default -> throw new IllegalArgumentException(target.toString());
              }
              done.countDown();
            });
  }

  @SuppressWarnings("unchecked")
  private static void addTo(List<?> list) {
    ((List<Object>) list).add("added");
  }

  @SuppressWarnings("unchecked")
  private static void putInto(Map<?, ?> map) {
    ((Map<String, Object>) map).put("added", true);
  }

  /** A registry holding {@code echo}, which waits for {@code mutated} and records its arguments. */
  private static ToolRegistry awaitingTool(
      CountDownLatch mutated, AtomicReference<Map<String, Object>> executed) {
    var echo =
        Tool.newBuilder()
            .withName("echo")
            .withDescription("echo")
            .withExecutor(
                (args, ctx) -> {
                  Await.latch("mutation", mutated);
                  executed.set(args);
                  return ToolResult.success("done");
                })
            .build();
    return new ToolRegistry(
        List.of(ToolBinding.newBuilder(echo).withCategory(ToolCategory.READ).build()));
  }
}
