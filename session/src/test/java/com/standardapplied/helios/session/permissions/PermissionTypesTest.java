/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PermissionTypesTest {

  // ── enums ────────────────────────────────────────────────────────────────

  @Test
  void permissionEffectEnumeratesAllThree() {
    assertEquals(3, PermissionEffect.values().length);
    assertSame(PermissionEffect.ALLOW, PermissionEffect.valueOf("ALLOW"));
    assertSame(PermissionEffect.ASK, PermissionEffect.valueOf("ASK"));
    assertSame(PermissionEffect.DENY, PermissionEffect.valueOf("DENY"));
  }

  @Test
  void permissionModeEnumeratesAllFive() {
    assertEquals(5, PermissionMode.values().length);
    assertSame(PermissionMode.DEFAULT, PermissionMode.valueOf("DEFAULT"));
    assertSame(PermissionMode.ACCEPT_EDITS, PermissionMode.valueOf("ACCEPT_EDITS"));
    assertSame(PermissionMode.BYPASS_PERMISSIONS, PermissionMode.valueOf("BYPASS_PERMISSIONS"));
    assertSame(PermissionMode.PLAN, PermissionMode.valueOf("PLAN"));
    assertSame(PermissionMode.LOCKED_DOWN, PermissionMode.valueOf("LOCKED_DOWN"));
  }

  // ── PermissionRule ───────────────────────────────────────────────────────

  @Test
  void ruleCanonicalConstructorRetainsFields() {
    var r = new PermissionRule(PermissionEffect.ALLOW, "Read", Optional.of("/x/**"));
    assertEquals(PermissionEffect.ALLOW, r.effect());
    assertEquals("Read", r.toolName());
    assertEquals(Optional.of("/x/**"), r.argPattern());
  }

  @Test
  void ruleRejectsNullEffect() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new PermissionRule(null, "Read", Optional.empty()));
    assertEquals("effect must not be null", ex.getMessage());
  }

  @Test
  void ruleRejectsNullToolName() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new PermissionRule(PermissionEffect.ALLOW, null, Optional.empty()));
    assertEquals("toolName must not be null", ex.getMessage());
  }

  @Test
  void ruleRejectsBlankToolName() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new PermissionRule(PermissionEffect.ALLOW, "  ", Optional.empty()));
    assertEquals("toolName must not be blank", ex.getMessage());
  }

  @Test
  void ruleRejectsNullArgPattern() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new PermissionRule(PermissionEffect.ALLOW, "Read", null));
    assertEquals("argPattern must not be null", ex.getMessage());
  }

  @Test
  void ruleRejectsBlankArgPatternWhenPresent() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new PermissionRule(PermissionEffect.ALLOW, "Read", Optional.of("  ")));
    assertEquals("argPattern must not be blank when present", ex.getMessage());
  }

  @Test
  void anyFactoryProducesEmptyArgPattern() {
    var r = PermissionRule.any(PermissionEffect.DENY, "Execute");
    assertTrue(r.argPattern().isEmpty());
  }

  @Test
  void withGlobFactoryProducesPresentArgPattern() {
    var r = PermissionRule.withGlob(PermissionEffect.ASK, "Write", "./**");
    assertEquals("./**", r.argPattern().orElseThrow());
  }

  // ── Permission ───────────────────────────────────────────────────────────

  @Test
  void permissionCanonicalConstructorDefensivelyCopiesLists() {
    var allow = new ArrayList<PermissionRule>();
    allow.add(PermissionRule.any(PermissionEffect.ALLOW, "Read"));
    var p = new Permission(PermissionMode.DEFAULT, allow, List.of(), List.of());
    allow.add(PermissionRule.any(PermissionEffect.DENY, "Sneaky"));
    assertEquals(1, p.allow().size(), "allow list must be defensively copied");
  }

  @Test
  void permissionRejectsNullMode() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new Permission(null, List.of(), List.of(), List.of()));
    assertEquals("mode must not be null", ex.getMessage());
  }

  @Test
  void permissionRejectsNullAllow() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new Permission(PermissionMode.DEFAULT, null, List.of(), List.of()));
    assertEquals("allow must not be null", ex.getMessage());
  }

  @Test
  void permissionRejectsNullAsk() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new Permission(PermissionMode.DEFAULT, List.of(), null, List.of()));
    assertEquals("ask must not be null", ex.getMessage());
  }

  @Test
  void permissionRejectsNullDeny() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new Permission(PermissionMode.DEFAULT, List.of(), List.of(), null));
    assertEquals("deny must not be null", ex.getMessage());
  }

  @Test
  void defaultInWorkspaceExposesExpectedRules() {
    var p = Permission.defaultInWorkspace();
    assertEquals(PermissionMode.DEFAULT, p.mode());
    assertEquals(6, p.allow().size());
    assertEquals(4, p.ask().size());
    assertTrue(p.deny().isEmpty());
  }

  @Test
  void planModeExposesExpectedRules() {
    var p = Permission.planMode();
    assertEquals(PermissionMode.PLAN, p.mode());
    assertEquals(4, p.allow().size());
    assertTrue(p.ask().isEmpty());
    assertTrue(p.deny().isEmpty());
  }

  @Test
  void emptyModeProducesEmptyRules() {
    var p = Permission.empty(PermissionMode.BYPASS_PERMISSIONS);
    assertEquals(PermissionMode.BYPASS_PERMISSIONS, p.mode());
    assertTrue(p.allow().isEmpty());
    assertTrue(p.ask().isEmpty());
    assertTrue(p.deny().isEmpty());
  }

  @Test
  void lockedDownAllowsOnlyExecuteAndAskUserQuestion() {
    var p = Permission.lockedDown();
    assertEquals(PermissionMode.LOCKED_DOWN, p.mode());
    assertEquals(2, p.allow().size());
    assertTrue(p.ask().isEmpty());
    assertTrue(p.deny().isEmpty());
    assertEquals(PermissionEffect.ALLOW, p.firstRuleFor("Execute").orElseThrow().effect());
    assertEquals(PermissionEffect.ALLOW, p.firstRuleFor("AskUserQuestion").orElseThrow().effect());
    assertTrue(p.firstRuleFor("Read").isEmpty(), "Read is not an explicit allow under lockedDown");
  }

  @Test
  void firstRuleForLooksUpAcrossLists() {
    var p =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(PermissionRule.any(PermissionEffect.ALLOW, "Read")),
            List.of(PermissionRule.any(PermissionEffect.ASK, "Write")),
            List.of(PermissionRule.any(PermissionEffect.DENY, "Execute")));
    assertEquals(PermissionEffect.ALLOW, p.firstRuleFor("Read").orElseThrow().effect());
    assertEquals(PermissionEffect.ASK, p.firstRuleFor("Write").orElseThrow().effect());
    assertEquals(PermissionEffect.DENY, p.firstRuleFor("Execute").orElseThrow().effect());
    assertTrue(p.firstRuleFor("Unknown").isEmpty());
  }

  @Test
  void firstRuleForRejectsNullName() {
    var p = Permission.defaultInWorkspace();
    assertThrows(NullPointerException.class, () -> p.firstRuleFor(null));
  }

  // ── PermissionDecision ───────────────────────────────────────────────────

  @Test
  void decisionFactoriesRetainEffectAndReason() {
    assertEquals(PermissionEffect.ALLOW, PermissionDecision.allow("ok").effect());
    assertEquals(PermissionEffect.ASK, PermissionDecision.ask("confirm").effect());
    assertEquals(PermissionEffect.DENY, PermissionDecision.deny("nope").effect());
  }

  @Test
  void decisionRejectsNullEffect() {
    var ex = assertThrows(NullPointerException.class, () -> new PermissionDecision(null, "r"));
    assertEquals("effect must not be null", ex.getMessage());
  }

  @Test
  void decisionRejectsNullReason() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new PermissionDecision(PermissionEffect.ALLOW, null));
    assertEquals("reason must not be null", ex.getMessage());
  }

  @Test
  void decisionRejectsBlankReason() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new PermissionDecision(PermissionEffect.ALLOW, "  "));
    assertEquals("reason must not be blank", ex.getMessage());
  }
}
