/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.session.loop.ApprovalRequest;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    var r = new PermissionRule("Read", Optional.of("/x/**"));
    assertEquals("Read", r.toolName());
    assertEquals(Optional.of("/x/**"), r.argPattern());
  }

  @Test
  void ruleRejectsNullToolName() {
    var ex =
        assertThrows(NullPointerException.class, () -> new PermissionRule(null, Optional.empty()));
    assertEquals("toolName must not be null", ex.getMessage());
  }

  @Test
  void ruleRejectsBlankToolName() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> new PermissionRule("  ", Optional.empty()));
    assertEquals("toolName must not be blank", ex.getMessage());
  }

  @Test
  void ruleRejectsNullArgPattern() {
    var ex = assertThrows(NullPointerException.class, () -> new PermissionRule("Read", null));
    assertEquals("argPattern must not be null", ex.getMessage());
  }

  @Test
  void ruleRejectsBlankArgPatternWhenPresent() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> new PermissionRule("Read", Optional.of("  ")));
    assertEquals("argPattern must not be blank when present", ex.getMessage());
  }

  @Test
  void anyFactoryProducesEmptyArgPattern() {
    assertEquals(new PermissionRule("Execute", Optional.empty()), PermissionRule.any("Execute"));
  }

  @Test
  void ofFactoryProducesPresentArgPattern() {
    assertEquals(
        new PermissionRule("Read", Optional.of("./src/**")), PermissionRule.of("Read", "./src/**"));
  }

  @Test
  void factoriesRejectBlankNamesAndGlobs() {
    assertThrows(IllegalArgumentException.class, () -> PermissionRule.any(" "));
    assertThrows(IllegalArgumentException.class, () -> PermissionRule.of(" ", "./**"));
    assertThrows(IllegalArgumentException.class, () -> PermissionRule.of("Read", " "));
  }

  @Test
  void ofRejectsANullGlob() {
    assertThrows(NullPointerException.class, () -> PermissionRule.of("Read", null));
  }

  // ── Permission ───────────────────────────────────────────────────────────

  @Test
  void permissionCanonicalConstructorDefensivelyCopiesLists() {
    var allow = new ArrayList<>(List.of(PermissionRule.any("Read")));
    var ask = new ArrayList<>(List.of(PermissionRule.any("Write")));
    var deny = new ArrayList<>(List.of(PermissionRule.any("Execute")));
    var p = new Permission(PermissionMode.DEFAULT, allow, ask, deny);

    allow.clear();
    ask.clear();
    deny.clear();

    assertEquals(List.of(PermissionRule.any("Read")), p.allow());
    assertEquals(List.of(PermissionRule.any("Write")), p.ask());
    assertEquals(List.of(PermissionRule.any("Execute")), p.deny());
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
  void permissionRejectsANullRule() {
    var rules = new ArrayList<PermissionRule>();
    rules.add(null);

    assertThrows(
        NullPointerException.class,
        () -> new Permission(PermissionMode.DEFAULT, List.of(), rules, List.of()));
  }

  @Test
  void defaultInWorkspaceOnlyAllowsMemoryWrites() {
    assertEquals(
        new Permission(
            PermissionMode.DEFAULT,
            List.of(PermissionRule.of("MemoryWrite", "/memories/**")),
            List.of(),
            List.of()),
        Permission.defaultInWorkspace());
  }

  @Test
  void planModeIsTheEmptyPlanPolicy() {
    assertEquals(Permission.empty(PermissionMode.PLAN), Permission.planMode());
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
    assertEquals(
        List.of(PermissionRule.any("Execute"), PermissionRule.any("AskUserQuestion")), p.allow());
    assertTrue(p.ask().isEmpty());
    assertTrue(p.deny().isEmpty());
  }

  // ── PermissionDecision ───────────────────────────────────────────────────

  @Test
  void decisionFactoriesRetainEffectAndReason() {
    assertEquals(PermissionEffect.ALLOW, PermissionDecision.allow("ok").effect());
    assertEquals(PermissionEffect.ASK, PermissionDecision.ask("confirm").effect());
    assertEquals(PermissionEffect.DENY, PermissionDecision.deny("nope").effect());
  }

  @Test
  void decisionFactoriesCarryNoRule() {
    assertEquals(Optional.empty(), PermissionDecision.allow("ok").rule());
    assertEquals(Optional.empty(), PermissionDecision.ask("confirm").rule());
    assertEquals(Optional.empty(), PermissionDecision.deny("nope").rule());
  }

  @Test
  void decisionRejectsNullRule() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new PermissionDecision(PermissionEffect.ALLOW, "r", null));
    assertEquals("rule must not be null", ex.getMessage());
  }

  @Test
  void decisionRejectsNullEffect() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new PermissionDecision(null, "r", Optional.empty()));
    assertEquals("effect must not be null", ex.getMessage());
  }

  @Test
  void decisionRejectsNullReason() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new PermissionDecision(PermissionEffect.ALLOW, null, Optional.empty()));
    assertEquals("reason must not be null", ex.getMessage());
  }

  @Test
  void decisionRejectsBlankReason() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new PermissionDecision(PermissionEffect.ALLOW, "  ", Optional.empty()));
    assertEquals("reason must not be blank", ex.getMessage());
  }

  // ── ApprovalRequest ──────────────────────────────────────────────────────

  private static final ToolCall CALL = new ToolCall("c", "Write", Map.of("path", "/tmp/a"));
  private static final ToolPermissionKey KEY = new ToolPermissionKey("Write", "/tmp/a");

  @Test
  void anApprovalRequestRejectsANullComponent() {
    var none = Optional.<PermissionRule>empty();
    var write = ToolCategory.WRITE;
    assertEquals(
        "call must not be null",
        assertThrows(
                NullPointerException.class,
                () -> new ApprovalRequest(null, "{}", KEY, write, none, "asked"))
            .getMessage());
    assertEquals(
        "arguments must not be null",
        assertThrows(
                NullPointerException.class,
                () -> new ApprovalRequest(CALL, null, KEY, write, none, "asked"))
            .getMessage());
    assertEquals(
        "key must not be null",
        assertThrows(
                NullPointerException.class,
                () -> new ApprovalRequest(CALL, "{}", null, write, none, "asked"))
            .getMessage());
    assertEquals(
        "category must not be null",
        assertThrows(
                NullPointerException.class,
                () -> new ApprovalRequest(CALL, "{}", KEY, null, none, "asked"))
            .getMessage());
    assertEquals(
        "rule must not be null",
        assertThrows(
                NullPointerException.class,
                () -> new ApprovalRequest(CALL, "{}", KEY, write, null, "asked"))
            .getMessage());
    assertEquals(
        "reason must not be null",
        assertThrows(
                NullPointerException.class,
                () -> new ApprovalRequest(CALL, "{}", KEY, write, none, null))
            .getMessage());
  }

  @Test
  void anApprovalRequestRejectsABlankReason() {
    var none = Optional.<PermissionRule>empty();
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ApprovalRequest(CALL, "{}", KEY, ToolCategory.WRITE, none, " "));
    assertEquals("reason must not be blank", ex.getMessage());
  }
}
