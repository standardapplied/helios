/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PostModelTurnHook;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link PostModelTurnHook} outcomes wired through AgentLoop + TurnRunner. */
final class PostModelTurnHookIntegrationTest {

  private final HookLoop loop = new HookLoop();

  @AfterEach
  void closeLoop() {
    loop.close();
  }

  @Test
  void postModelTurnInjectQueuesAndContinues() {
    var calls = new AtomicInteger();
    PostModelTurnHook injector =
        (response, ctx) ->
            calls.incrementAndGet() == 1 ? HookOutcome.inject("revise") : HookOutcome.cont();
    var model =
        ScriptedModel.newBuilder()
            .withTextTurn("v1", Usage.of(1, 1))
            .withTextTurn("v2", Usage.of(1, 1))
            .build();
    var result = loop.run(model, UserMessage.text("hi"), injector);
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("v2", success.result());
  }
}
