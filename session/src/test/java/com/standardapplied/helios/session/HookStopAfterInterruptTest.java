/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.EndingSessions.binding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.OnUserMessageHook;
import com.standardapplied.helios.session.hooks.PostModelTurnHook;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** A hook's {@code Stop} after the run was interrupted ends the run {@code Cancelled}. */
final class HookStopAfterInterruptTest {

  private static final ToolCall ECHO = new ToolCall("c1", "echo", Map.of("v", "one"));

  static Stream<Arguments> stoppingHooks() {
    return Stream.of(
        Arguments.of(
            "OnUserMessage",
            (Function<HeldStop, Hook>)
                held -> (OnUserMessageHook) (message, ctx) -> held.stop(ctx)),
        Arguments.of(
            "PreModelTurn",
            (Function<HeldStop, Hook>) held -> (PreModelTurnHook) (history, ctx) -> held.stop(ctx)),
        Arguments.of(
            "PostModelTurn",
            (Function<HeldStop, Hook>)
                held -> (PostModelTurnHook) (response, ctx) -> held.stop(ctx)),
        Arguments.of(
            "PreToolUse",
            (Function<HeldStop, Hook>) held -> (PreToolUseHook) (call, ctx) -> held.stop(ctx)),
        Arguments.of(
            "PostToolUse",
            (Function<HeldStop, Hook>)
                held -> (PostToolUseHook) (call, result, ctx) -> held.stop(ctx)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("stoppingHooks")
  void anInterruptWhileAHookRunsEndsTheRunCancelledThoughTheHookStopsIt(
      String phase, Function<HeldStop, Hook> hook) {
    var model = ScriptedModel.newBuilder().withToolCallsTurn(ECHO).withTextTurn("one").build();
    var held = new HeldStop(new CountDownLatch(1), new CountDownLatch(1));
    var echo = binding("echo", (args, ctx) -> ToolResult.success("echoed"));
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSessionId("sess-hook-stop")
            .withTools(List.of(echo))
            .withHook(hook.apply(held));

    try (var session = AgentSession.open(options.build())) {
      var run = session.send("go");
      Await.latch("the " + phase + " hook to run", held.entered());
      session.interrupt();
      held.release().countDown();

      var cancelled = assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the run", run));
      assertEquals("interrupted", cancelled.reason());
    }
  }

  /** A hook body that waits for {@code release}, then stops the run its interrupt cancelled. */
  record HeldStop(CountDownLatch entered, CountDownLatch release) {
    HookOutcome.Stop stop(HookContext ctx) {
      entered.countDown();
      Uninterruptible.await(release);
      assertTrue(ctx.cancellation().isCancelled(), "the interrupt cancelled the run's token");
      return HookOutcome.stop("hook stopped");
    }
  }
}
