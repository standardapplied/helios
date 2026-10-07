/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link PostToolUseHook} outcomes wired through AgentLoop + TurnRunner. */
final class PostToolUseHookIntegrationTest {

  private final HookLoop loop = new HookLoop();

  @AfterEach
  void closeLoop() {
    loop.close();
  }

  private ResultMessage callEcho(ScriptedModel.Builder thenTurns, PostToolUseHook hook) {
    return loop.run(thenTurns.build(), HookLoop.echoTool(), UserMessage.text("call echo"), hook);
  }

  private static ScriptedModel.Builder echoTurn() {
    return ScriptedModel.newBuilder().withToolCallsTurn(HookLoop.echoCall(Map.of("v", "x")));
  }

  @Test
  void postToolUseMutateRewritesResultOutput() {
    PostToolUseHook rewriter = (call, result, ctx) -> HookOutcome.mutateResult("REWRITTEN");
    callEcho(echoTurn().withTextTurn("done"), rewriter);
    assertEquals("REWRITTEN", loop.toolMessage().content());
  }

  @Test
  void postToolUseInjectQueuesMessageButLoopContinues() {
    var calls = new AtomicInteger();
    PostToolUseHook injector =
        (call, result, ctx) ->
            calls.incrementAndGet() == 1 ? HookOutcome.inject("retry") : HookOutcome.cont();
    var result = callEcho(echoTurn().withTextTurn("done"), injector);
    assertInstanceOf(ResultMessage.Success.class, result);
  }

  @Test
  void postToolUseStopTerminatesAfterToolMessageAppended() {
    PostToolUseHook stopper = (call, result, ctx) -> HookOutcome.stop("after-tool-stop");
    var result = callEcho(echoTurn(), stopper);
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("after-tool-stop", success.result());
    // Tool message was appended before the Stop fired
    assertEquals("echoed: x", loop.toolMessage().content());
  }
}
