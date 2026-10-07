/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link PreStopHook} outcomes wired through AgentLoop + TurnRunner. */
final class PreStopHookIntegrationTest {

  private final HookLoop loop = new HookLoop();

  @AfterEach
  void closeLoop() {
    loop.close();
  }

  @Test
  void preStopInjectQueuesMessageAndContinuesLoop() {
    var calls = new AtomicInteger();
    PreStopHook injector =
        (response, ctx) ->
            calls.incrementAndGet() == 1 ? HookOutcome.inject("more please") : HookOutcome.cont();
    var model =
        ScriptedModel.newBuilder()
            .withTextTurn("draft", Usage.of(1, 1))
            .withTextTurn("final", Usage.of(1, 1))
            .build();
    var result = loop.run(model, UserMessage.text("first"), injector);
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("final", success.result(), "second turn drove the terminal success");
  }

  @Test
  void preStopStopOverridesResultText() {
    PreStopHook overrider = (response, ctx) -> HookOutcome.stop("override-text");
    var model = ScriptedModel.newBuilder().withTextTurn("draft", Usage.of(1, 1)).build();
    var result = loop.run(model, UserMessage.text("hi"), overrider);
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("override-text", success.result());
  }
}
