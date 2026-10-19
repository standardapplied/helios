/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

final class HookDecisionTest {

  private static final HookOutcome BLOCK = HookOutcome.block("nope");

  @Test
  void proceedSingletonIsContinueWithNoName() {
    var d = HookDecision.proceed();
    assertTrue(d.shouldContinue());
    assertInstanceOf(HookOutcome.Continue.class, d.outcome());
    assertNull(d.firingHookName());
  }

  @Test
  void proceedIsSingleton() {
    assertSame(HookDecision.proceed(), HookDecision.proceed());
  }

  @Test
  void ofWrapsNameAndOutcome() {
    var d = HookDecision.of("myHook", BLOCK);
    assertFalse(d.shouldContinue());
    assertSame(BLOCK, d.outcome());
    assertEquals("myHook", d.firingHookName());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " \t"})
  void ofRejectsAMissingName(String name) {
    var ex = assertThrows(IllegalArgumentException.class, () -> HookDecision.of(name, BLOCK));
    assertEquals(
        "firingHookName must not be blank when outcome is non-Continue, got " + BLOCK,
        ex.getMessage());
  }

  @Test
  void ofRejectsNullOutcome() {
    var ex = assertThrows(NullPointerException.class, () -> HookDecision.of("myHook", null));
    assertEquals("outcome must not be null", ex.getMessage());
  }

  @Test
  void ofRejectsContinueOutcome() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> HookDecision.of("myHook", HookOutcome.cont()));
    assertEquals("use HookDecision.proceed() for Continue outcomes", ex.getMessage());
  }

  @Test
  void constructorRejectsNullOutcome() {
    var ex = assertThrows(NullPointerException.class, () -> new HookDecision("myHook", null));
    assertEquals("outcome must not be null", ex.getMessage());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " \t"})
  void constructorRejectsANonContinueOutcomeWithoutAName(String name) {
    var ex = assertThrows(IllegalArgumentException.class, () -> new HookDecision(name, BLOCK));
    assertEquals(
        "firingHookName must not be blank when outcome is non-Continue, got " + BLOCK,
        ex.getMessage());
  }

  @Test
  void constructorRejectsAContinueOutcomeWithAName() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> new HookDecision("myHook", HookOutcome.cont()));
    assertEquals(
        "firingHookName must be null when outcome is Continue, got myHook", ex.getMessage());
  }

  @Test
  void constructorAllowsContinueWithoutAName() {
    var d = new HookDecision(null, HookOutcome.cont());
    assertTrue(d.shouldContinue());
  }
}
