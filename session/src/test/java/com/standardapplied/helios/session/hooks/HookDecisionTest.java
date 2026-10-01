/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class HookDecisionTest {

  private static final PreToolUseHook NAMED_HOOK =
      new PreToolUseHook() {
        @Override
        public HookOutcome beforeTool(ai.singlr.core.model.ToolCall call, HookContext ctx) {
          return HookOutcome.cont();
        }

        @Override
        public String name() {
          return "myHook";
        }
      };

  @Test
  void proceedSingletonIsContinue() {
    var d = HookDecision.proceed();
    assertTrue(d.shouldContinue());
    assertInstanceOf(HookOutcome.Continue.class, d.outcome());
    assertTrue(d.firingHookOptional().isEmpty());
  }

  @Test
  void proceedIsSingleton() {
    assertSame(HookDecision.proceed(), HookDecision.proceed());
  }

  @Test
  void ofWrapsHookAndOutcome() {
    var d = HookDecision.of(NAMED_HOOK, HookOutcome.block("nope"));
    assertFalse(d.shouldContinue());
    assertInstanceOf(HookOutcome.Block.class, d.outcome());
    assertEquals(NAMED_HOOK, d.firingHookOptional().orElseThrow());
    assertEquals("myHook", d.firingHook().name());
  }

  @Test
  void ofRejectsNullHook() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> HookDecision.of(null, HookOutcome.block("nope")));
    assertEquals("hook must not be null", ex.getMessage());
  }

  @Test
  void ofRejectsNullOutcome() {
    assertThrows(NullPointerException.class, () -> HookDecision.of(NAMED_HOOK, null));
  }

  @Test
  void ofRejectsContinueOutcome() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> HookDecision.of(NAMED_HOOK, HookOutcome.cont()));
    assertTrue(ex.getMessage().contains("HookDecision.proceed()"));
  }

  @Test
  void constructorRejectsNullOutcome() {
    assertThrows(NullPointerException.class, () -> new HookDecision(NAMED_HOOK, null));
  }

  @Test
  void constructorRejectsNonContinueWithoutHook() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new HookDecision(null, HookOutcome.block("nope")));
    assertTrue(ex.getMessage().contains("firingHook must not be null"));
  }

  @Test
  void constructorAllowsContinueWithoutHook() {
    var d = new HookDecision(null, HookOutcome.cont());
    assertTrue(d.shouldContinue());
  }
}
