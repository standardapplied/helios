/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link PreModelTurnHook} outcomes wired through AgentLoop + TurnRunner. */
final class PreModelTurnHookIntegrationTest {

  private final HookLoop loop = new HookLoop();

  @AfterEach
  void closeLoop() {
    loop.close();
  }

  @Test
  void preModelTurnInjectSkipsTheModelCallForThatTurn() {
    var calls = new AtomicInteger();
    PreModelTurnHook injector =
        (history, ctx) ->
            calls.incrementAndGet() == 1 ? HookOutcome.inject("after-inject") : HookOutcome.cont();
    var countingModel = ScriptedModel.newBuilder().withTextTurn("ok").build();
    var result = loop.run(countingModel, UserMessage.text("first"), injector);
    assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals(
        1, countingModel.calls().size(), "first turn skipped, second turn called the model once");
  }

  @Test
  void preModelTurnStopTerminates() {
    PreModelTurnHook stopper = (history, ctx) -> HookOutcome.stop("pre-turn-stop");
    var model = ScriptedModel.newBuilder().withTextTurn("").build();
    var result = loop.run(model, UserMessage.text("hi"), stopper);
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("pre-turn-stop", success.result());
  }
}
