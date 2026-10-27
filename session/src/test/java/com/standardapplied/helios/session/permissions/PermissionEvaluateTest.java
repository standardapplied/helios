/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** {@link Permission#evaluate}, the policy's pure decision for one key: the host's dry run. */
final class PermissionEvaluateTest {

  private static Permission rules(
      PermissionMode mode,
      List<PermissionRule> allow,
      List<PermissionRule> ask,
      List<PermissionRule> deny) {
    return new Permission(mode, allow, ask, deny);
  }

  private static PermissionDecision byRule(
      PermissionEffect effect, PermissionRule rule, String reason) {
    return new PermissionDecision(effect, reason, Optional.of(rule));
  }

  // ── the dry run ──────────────────────────────────────────────────────────

  private static final PermissionRule READ_SRC = PermissionRule.of("Read", "./src/**");
  private static final PermissionRule NO_RM = PermissionRule.of("Execute", "BASH/rm **");
  private static final Permission PRODUCTION =
      rules(PermissionMode.DEFAULT, List.of(READ_SRC), List.of(), List.of(NO_RM));

  @Test
  void aDryRunDeniesTheCommandItsDenyRuleNames() {
    assertEquals(
        byRule(
            PermissionEffect.DENY,
            NO_RM,
            "deny rule Execute(BASH/rm **) matched Execute(BASH/rm -rf ~)"),
        PRODUCTION.evaluate(
            new ToolPermissionKey("Execute", "BASH/rm -rf ~"), ToolCategory.EXECUTION));
  }

  @Test
  void aDryRunAllowsWhatItsAllowRuleNames() {
    assertEquals(
        byRule(PermissionEffect.ALLOW, READ_SRC, "allow rule Read(./src/**) matched Read(./src/a)"),
        PRODUCTION.evaluate(new ToolPermissionKey("Read", "./src/a"), ToolCategory.READ));
  }

  @Test
  void aDryRunAsksWhereTheModeAsksAndNamesNoRule() {
    assertEquals(
        PermissionDecision.ask("default-ask on WRITE (Write(./a))"),
        PRODUCTION.evaluate(new ToolPermissionKey("Write", "./a"), ToolCategory.WRITE));
  }

  @Test
  void evaluateRejectsNullArguments() {
    var key = ToolPermissionKey.of("Read");
    assertThrows(NullPointerException.class, () -> PRODUCTION.evaluate(null, ToolCategory.READ));
    assertThrows(NullPointerException.class, () -> PRODUCTION.evaluate(key, null));
  }

  // ── rules ────────────────────────────────────────────────────────────────

  @Test
  void anAskRuleWinsOverAnAllowForTheSameCall() {
    var ask = PermissionRule.any("Write");
    var permission =
        rules(
            PermissionMode.DEFAULT, List.of(PermissionRule.any("Write")), List.of(ask), List.of());
    assertEquals(
        byRule(PermissionEffect.ASK, ask, "ask rule Write matched Write"),
        permission.evaluate(ToolPermissionKey.of("Write"), ToolCategory.WRITE));
  }

  @Test
  void anAskRuleOverridesAnAllowingCategoryDefault() {
    var ask = PermissionRule.any("Read");
    var permission = rules(PermissionMode.DEFAULT, List.of(), List.of(ask), List.of());
    assertEquals(
        byRule(PermissionEffect.ASK, ask, "ask rule Read matched Read"),
        permission.evaluate(ToolPermissionKey.of("Read"), ToolCategory.READ));
  }

  private static final PermissionRule DENY_TMP = PermissionRule.of("Write", "/tmp/**");
  private static final PermissionRule ASK_TMP = PermissionRule.of("Write", "/tmp/*");
  private static final PermissionRule ALLOW_TMP_A = PermissionRule.of("Write", "/tmp/?");

  /**
   * Which of a deny, an ask and an allow rule matching one key a policy holds, and the effect that
   * decides.
   */
  record Rules(boolean deny, boolean ask, boolean allow, PermissionEffect expected) {}

  /** Every combination of the three rules, in every mode. */
  static Stream<Arguments> ruleCombinations() {
    var combinations =
        List.of(
            new Rules(true, true, true, PermissionEffect.DENY),
            new Rules(true, true, false, PermissionEffect.DENY),
            new Rules(true, false, true, PermissionEffect.DENY),
            new Rules(true, false, false, PermissionEffect.DENY),
            new Rules(false, true, true, PermissionEffect.ASK),
            new Rules(false, true, false, PermissionEffect.ASK),
            new Rules(false, false, true, PermissionEffect.ALLOW));
    return Stream.of(PermissionMode.values())
        .flatMap(mode -> combinations.stream().map(rules -> Arguments.of(mode, rules)));
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("ruleCombinations")
  void theMostRestrictiveMatchingRuleDecidesInEveryMode(PermissionMode mode, Rules held) {
    var permission =
        rules(
            mode,
            held.allow() ? List.of(ALLOW_TMP_A) : List.of(),
            held.ask() ? List.of(ASK_TMP) : List.of(),
            held.deny() ? List.of(DENY_TMP) : List.of());
    var rule =
        switch (held.expected()) {
          case DENY -> DENY_TMP;
          case ASK -> ASK_TMP;
          case ALLOW -> ALLOW_TMP_A;
        };

    var decision =
        permission.evaluate(new ToolPermissionKey("Write", "/tmp/a"), ToolCategory.WRITE);

    assertEquals(held.expected(), decision.effect());
    assertEquals(Optional.of(rule), decision.rule());
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "DEFAULT, ASK",
    "PLAN, DENY",
    "ACCEPT_EDITS, ALLOW",
    "LOCKED_DOWN, DENY",
    "BYPASS_PERMISSIONS, ALLOW"
  })
  void withNoMatchingRuleTheModesCategoryDefaultDecides(
      PermissionMode mode, PermissionEffect expected) {
    var permission = rules(mode, List.of(ALLOW_TMP_A), List.of(ASK_TMP), List.of(DENY_TMP));

    var decision =
        permission.evaluate(new ToolPermissionKey("Write", "/var/a/b"), ToolCategory.WRITE);

    assertEquals(new PermissionDecision(expected, decision.reason(), Optional.empty()), decision);
  }

  // ── a rule decides regardless of the category ────────────────────────────

  private static final PermissionRule DENY_SECRETS = PermissionRule.of("Grep", "./secrets/**");
  private static final PermissionRule ALLOW_PUBLIC = PermissionRule.of("Grep", "./docs/public/**");
  private static final PermissionRule ASK_DOCS = PermissionRule.of("Grep", "./docs/**");

  private static Optional<PermissionRule> decidingRule(
      PermissionMode mode, String tool, String path, ToolCategory category) {
    return rules(mode, List.of(ALLOW_PUBLIC), List.of(ASK_DOCS), List.of(DENY_SECRETS))
        .evaluate(new ToolPermissionKey(tool, path), category)
        .rule();
  }

  @ParameterizedTest
  @EnumSource(ToolCategory.class)
  void theDecidingRuleIsTheSameForEveryCategory(ToolCategory category) {
    var mode = PermissionMode.DEFAULT;
    assertEquals(Optional.of(DENY_SECRETS), decidingRule(mode, "Grep", "./secrets/key", category));
    assertEquals(
        Optional.of(ASK_DOCS), decidingRule(mode, "Grep", "./docs/public/a.txt", category));
    assertEquals(Optional.of(ASK_DOCS), decidingRule(mode, "Grep", "./docs/a.txt", category));
    assertEquals(Optional.empty(), decidingRule(mode, "Grep", "./src/a.txt", category));
    assertEquals(Optional.empty(), decidingRule(mode, "Read", "./secrets/key", category));
  }

  @Test
  void underBypassPermissionsDenyAndAskRulesDecide() {
    var mode = PermissionMode.BYPASS_PERMISSIONS;
    var read = ToolCategory.READ;
    assertEquals(Optional.of(DENY_SECRETS), decidingRule(mode, "Grep", "./secrets/key", read));
    assertEquals(Optional.of(ASK_DOCS), decidingRule(mode, "Grep", "./docs/public/a.txt", read));
    assertEquals(Optional.empty(), decidingRule(mode, "Grep", "./src/a.txt", read));
  }

  // ── category defaults per mode ───────────────────────────────────────────

  @ParameterizedTest(name = "{0} {1} -> {2}")
  @CsvSource({
    "DEFAULT, READ, ALLOW, default-allow READ (T)",
    "DEFAULT, WRITE, ASK, default-ask on WRITE (T)",
    "DEFAULT, EXECUTION, ASK, default-ask on EXECUTION (T)",
    "DEFAULT, NETWORK, ASK, default-ask on NETWORK (T)",
    "DEFAULT, CONTROL, ALLOW, default-allow CONTROL (T)",
    "PLAN, READ, ALLOW, default-allow under PLAN (T)",
    "PLAN, WRITE, DENY, PLAN mode forbids writes (T)",
    "PLAN, EXECUTION, DENY, PLAN mode forbids execution (T)",
    "PLAN, NETWORK, ASK, default-ask on NETWORK (T)",
    "PLAN, CONTROL, ALLOW, default-allow CONTROL (T)",
    "ACCEPT_EDITS, READ, ALLOW, default-allow READ (T)",
    "ACCEPT_EDITS, WRITE, ALLOW, ACCEPT_EDITS allows writes (T)",
    "ACCEPT_EDITS, EXECUTION, ASK, default-ask on EXECUTION (T)",
    "BYPASS_PERMISSIONS, READ, ALLOW, BYPASS_PERMISSIONS allows T",
    "BYPASS_PERMISSIONS, WRITE, ALLOW, BYPASS_PERMISSIONS allows T",
    "BYPASS_PERMISSIONS, EXECUTION, ALLOW, BYPASS_PERMISSIONS allows T",
    "BYPASS_PERMISSIONS, NETWORK, ALLOW, BYPASS_PERMISSIONS allows T",
    "BYPASS_PERMISSIONS, CONTROL, ALLOW, BYPASS_PERMISSIONS allows T"
  })
  void theModeAndCategoryDecideWhenNoRuleMatches(
      PermissionMode mode, ToolCategory category, PermissionEffect effect, String reason) {
    assertEquals(
        new PermissionDecision(effect, reason, Optional.empty()),
        Permission.empty(mode).evaluate(ToolPermissionKey.of("T"), category));
  }

  @ParameterizedTest
  @EnumSource(ToolCategory.class)
  void lockedDownDeniesEveryCategoryByDefault(ToolCategory category) {
    assertEquals(
        PermissionDecision.deny(
            "LOCKED_DOWN mode forbids "
                + category
                + " by default (T) — add an explicit allow rule to open this tool"),
        Permission.empty(PermissionMode.LOCKED_DOWN).evaluate(ToolPermissionKey.of("T"), category));
  }
}
