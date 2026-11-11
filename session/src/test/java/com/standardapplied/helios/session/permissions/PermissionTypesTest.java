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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

  private static final PermissionRule READ_SRC = PermissionRule.of("Read", "./src/**");
  private static final PermissionRule READ_DOCS = PermissionRule.of("Read", "./docs/**");
  private static final PermissionRule WRITE_TMP = PermissionRule.of("Write", "/tmp/**");
  private static final PermissionRule WRITE_ANY = PermissionRule.any("Write");
  private static final PermissionRule EXECUTE_RM = PermissionRule.of("Execute", "BASH/rm **");
  private static final PermissionRule READ_SECRETS = PermissionRule.of("Read", "**/secrets.*");

  @Test
  void aBuilderPutsEachRuleInTheListItsMethodNames() {
    var p =
        Permission.newBuilder(PermissionMode.ACCEPT_EDITS)
            .withAllow(READ_SRC, READ_DOCS)
            .withAsk(WRITE_TMP, WRITE_ANY)
            .withDeny(EXECUTE_RM, READ_SECRETS)
            .build();

    assertEquals(PermissionMode.ACCEPT_EDITS, p.mode());
    assertEquals(List.of(READ_SRC, READ_DOCS), p.allow());
    assertEquals(List.of(WRITE_TMP, WRITE_ANY), p.ask());
    assertEquals(List.of(EXECUTE_RM, READ_SECRETS), p.deny());
  }

  @Test
  void repeatedCallsAppendInOrder() {
    var p =
        Permission.newBuilder(PermissionMode.DEFAULT)
            .withDeny(READ_SECRETS)
            .withAllow(READ_DOCS)
            .withAsk(WRITE_ANY)
            .withDeny(EXECUTE_RM)
            .withAllow(READ_SRC, WRITE_TMP)
            .withAsk()
            .withAsk(WRITE_TMP)
            .build();

    assertEquals(List.of(READ_DOCS, READ_SRC, WRITE_TMP), p.allow());
    assertEquals(List.of(WRITE_ANY, WRITE_TMP), p.ask());
    assertEquals(List.of(READ_SECRETS, EXECUTE_RM), p.deny());
  }

  @Test
  void aBuiltPermissionKeepsItsRulesWhileItsBuilderGrows() {
    var builder =
        Permission.newBuilder(PermissionMode.DEFAULT)
            .withAllow(READ_SRC)
            .withAsk(WRITE_TMP)
            .withDeny(EXECUTE_RM);
    var p = builder.build();

    builder.withAllow(READ_DOCS).withAsk(WRITE_ANY).withDeny(READ_SECRETS);

    assertEquals(List.of(READ_SRC), p.allow());
    assertEquals(List.of(WRITE_TMP), p.ask());
    assertEquals(List.of(EXECUTE_RM), p.deny());
    assertThrows(UnsupportedOperationException.class, () -> p.allow().add(READ_DOCS));
  }

  @ParameterizedTest
  @EnumSource(PermissionMode.class)
  void anEmptyBuilderBuildsTheEmptyPolicy(PermissionMode mode) {
    var p = Permission.newBuilder(mode).build();

    assertEquals(mode, p.mode());
    assertEquals(List.of(), p.allow());
    assertEquals(List.of(), p.ask());
    assertEquals(List.of(), p.deny());
    assertEquals(Permission.empty(mode), p);
  }

  @Test
  void aBuilderRejectsANullMode() {
    var ex = assertThrows(NullPointerException.class, () -> Permission.newBuilder(null));
    assertEquals("mode must not be null", ex.getMessage());
  }

  @Test
  void aBuilderRejectsANullRuleOrArray() {
    var builder = Permission.newBuilder(PermissionMode.DEFAULT);
    var none = (PermissionRule[]) null;

    assertThrows(NullPointerException.class, () -> builder.withAllow(READ_SRC, null));
    assertThrows(NullPointerException.class, () -> builder.withAsk(WRITE_TMP, null));
    assertThrows(NullPointerException.class, () -> builder.withDeny(EXECUTE_RM, null));
    assertThrows(NullPointerException.class, () -> builder.withAllow(none));
    assertThrows(NullPointerException.class, () -> builder.withAsk(none));
    assertThrows(NullPointerException.class, () -> builder.withDeny(none));
    assertEquals(Permission.empty(PermissionMode.DEFAULT), builder.build());
  }

  @Test
  void defaultInWorkspaceOnlyAllowsMemoryWrites() {
    var p = Permission.defaultInWorkspace();
    assertEquals(PermissionMode.DEFAULT, p.mode());
    assertEquals(List.of(PermissionRule.of("MemoryWrite", "/memories/**")), p.allow());
    assertEquals(List.of(), p.ask());
    assertEquals(List.of(), p.deny());
  }

  @Test
  void planModeIsTheEmptyPlanPolicy() {
    var p = Permission.planMode();
    assertEquals(PermissionMode.PLAN, p.mode());
    assertEquals(List.of(), p.allow());
    assertEquals(List.of(), p.ask());
    assertEquals(List.of(), p.deny());
    assertEquals(Permission.empty(PermissionMode.PLAN), p);
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
