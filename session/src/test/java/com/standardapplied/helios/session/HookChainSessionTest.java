/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongFunction;
import org.junit.jupiter.api.Test;

/**
 * The hooks of one phase run by priority, and the first that answers anything but {@code Continue}
 * decides for the phase: no hook after it runs.
 */
final class HookChainSessionTest {

  /** A {@code PreModelTurn} hook of the given priority answering by turn index. */
  private record Ranked(int priority, LongFunction<HookOutcome.PreModelTurn> byTurn)
      implements PreModelTurnHook {

    @Override
    public HookOutcome.PreModelTurn beforeModelTurn(List<Message> history, HookContext ctx) {
      return byTurn.apply(ctx.turnIndex());
    }
  }

  private final List<Long> secondRanOnTurns = new CopyOnWriteArrayList<>();

  private Ranked second() {
    return new Ranked(
        2,
        turn -> {
          secondRanOnTurns.add(turn);
          return turn == 1 ? HookOutcome.stop("stopped by the second") : HookOutcome.cont();
        });
  }

  private static ResultMessage run(ScriptedModel model, PreModelTurnHook... hooks) {
    var options = SessionOptions.newBuilder().withModel(model).withHooks(List.of(hooks)).build();
    try (var session = AgentSession.open(options)) {
      var future = session.send(UserMessage.text("go"));
      return Await.value("the session's result", future);
    }
  }

  @Test
  void anInjectFromTheFirstHookKeepsTheSecondFromRunningAndTheSessionGoesOnWithTheMessage() {
    var model = ScriptedModel.newBuilder().withTextTurn("done").build();
    var first =
        new Ranked(1, turn -> turn == 1 ? HookOutcome.inject("injected") : HookOutcome.cont());

    var result = run(model, second(), first);

    assertEquals("done", assertInstanceOf(ResultMessage.Success.class, result).result());
    assertEquals(List.of(2L), secondRanOnTurns);
    assertEquals(Message.user("injected"), model.calls().getLast().getLast());
  }

  @Test
  void aContinueFromTheFirstHookLetsTheSecondStopTheSession() {
    var model = ScriptedModel.newBuilder().build();
    var first = new Ranked(1, turn -> HookOutcome.cont());

    var result = run(model, second(), first);

    assertEquals(
        "stopped by the second", assertInstanceOf(ResultMessage.Success.class, result).result());
    assertEquals(List.of(1L), secondRanOnTurns);
    assertEquals(List.of(), model.calls());
  }
}
