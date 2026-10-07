/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.Map;
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
    assertEquals("not allowed", loop.first(QueryEvent.ToolBlocked.class).reason());
    var toolResult = loop.first(QueryEvent.ToolResult.class);
    assertTrue(toolResult.result().output().contains("blocked by hook"));
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
}
