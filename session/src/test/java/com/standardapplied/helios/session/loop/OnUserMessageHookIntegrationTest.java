/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FileReference;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.OnUserMessageHook;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link OnUserMessageHook} outcomes wired through AgentLoop + TurnRunner. */
final class OnUserMessageHookIntegrationTest {

  private final HookLoop loop = new HookLoop();

  @AfterEach
  void closeLoop() {
    loop.close();
  }

  @Test
  void onUserMessageBlockDropsTheMessage() {
    OnUserMessageHook blocker = (msg, ctx) -> HookOutcome.block("PII");
    var dropped = UserMessage.text("secret");
    var model = ScriptedModel.newBuilder().withTextTurn("never").build();
    var result = loop.run(model, dropped, blocker);
    // Message blocked → history stays empty → loop terminates with EmptyHistory error.
    assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result);
    assertTrue(loop.eventsOf(QueryEvent.UserMessageReceived.class).isEmpty());
    assertTrue(
        loop.eventsOf(QueryEvent.HookFired.class).stream()
            .anyMatch(h -> h.outcomeKind().equals("Block")));
    // The dropped message surfaces as MessageBlocked so UIs/audit can render the drop.
    var blocked = loop.first(QueryEvent.MessageBlocked.class);
    assertSame(dropped, blocked.message());
    assertEquals("PII", blocked.reason());
    assertFalse(blocked.hookName().isBlank(), "hookName must be non-blank");
  }

  @Test
  void onUserMessageMutateRewritesText() {
    OnUserMessageHook rewriter = (msg, ctx) -> HookOutcome.mutateText("REDACTED");
    var reference = FileReference.of("https://example.com/video.mp4", "video/mp4");
    var message =
        UserMessage.newBuilder().withText("PII data").withFileReference(reference).build();
    var model = ScriptedModel.newBuilder().withTextTurn("ok").build();
    loop.run(model, message, rewriter);
    assertEquals("REDACTED", loop.history().get(0).content());
    assertEquals(List.of(reference), loop.history().get(0).fileReferences());
  }

  @Test
  void onUserMessageStopTerminatesSession() {
    OnUserMessageHook stopper = (msg, ctx) -> HookOutcome.stop("blocked-by-hook");
    var model = ScriptedModel.newBuilder().withTextTurn("").build();
    var result = loop.run(model, UserMessage.text("hi"), stopper);
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("blocked-by-hook", success.result());
  }

  @Test
  void continueOutcomesDoNotEmitHookFired() {
    OnUserMessageHook noOp = (msg, ctx) -> HookOutcome.cont();
    PreModelTurnHook noOp2 = (history, ctx) -> HookOutcome.cont();
    var model = ScriptedModel.newBuilder().withTextTurn("ok").build();
    loop.run(model, UserMessage.text("hi"), noOp, noOp2);
    assertTrue(
        loop.eventsOf(QueryEvent.HookFired.class).isEmpty(),
        "Continue outcomes must not emit HookFired");
  }
}
