/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.session.tools.ToolPermissionKey;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RuleMatcherTest {

  private final RuleMatcher matcher = new RuleMatcher();

  // ── matches() basics ─────────────────────────────────────────────────────

  @Test
  void differentToolNameDoesNotMatch() {
    var rule = PermissionRule.any(PermissionEffect.ALLOW, "Read");
    var key = ToolPermissionKey.of("Write");
    assertFalse(matcher.matches(key, rule));
  }

  @Test
  void sameToolNameWithAbsentPatternMatches() {
    var rule = PermissionRule.any(PermissionEffect.ALLOW, "Read");
    var key = new ToolPermissionKey("Read", "/anything");
    assertTrue(matcher.matches(key, rule));
  }

  @Test
  void exactPathMatches() {
    var rule = PermissionRule.withGlob(PermissionEffect.ALLOW, "Read", "/workspace/foo.txt");
    var key = new ToolPermissionKey("Read", "/workspace/foo.txt");
    assertTrue(matcher.matches(key, rule));
  }

  @Test
  void singleStarMatchesOneSegment() {
    var rule = PermissionRule.withGlob(PermissionEffect.ALLOW, "Read", "/workspace/*");
    assertTrue(matcher.matches(new ToolPermissionKey("Read", "/workspace/foo.txt"), rule));
    assertFalse(matcher.matches(new ToolPermissionKey("Read", "/workspace/sub/foo.txt"), rule));
  }

  @Test
  void doubleStarMatchesAnyDepth() {
    var rule = PermissionRule.withGlob(PermissionEffect.ALLOW, "Read", "/workspace/**");
    assertTrue(matcher.matches(new ToolPermissionKey("Read", "/workspace/foo.txt"), rule));
    assertTrue(matcher.matches(new ToolPermissionKey("Read", "/workspace/sub/deep/foo.txt"), rule));
  }

  @Test
  void questionMarkMatchesSingleCharacter() {
    var rule = PermissionRule.withGlob(PermissionEffect.ALLOW, "Read", "/a/?.txt");
    assertTrue(matcher.matches(new ToolPermissionKey("Read", "/a/x.txt"), rule));
    assertFalse(matcher.matches(new ToolPermissionKey("Read", "/a/xy.txt"), rule));
  }

  @Test
  void regexMetaCharsEscaped() {
    var rule = PermissionRule.withGlob(PermissionEffect.ALLOW, "Read", "/a.b/(c)");
    assertTrue(matcher.matches(new ToolPermissionKey("Read", "/a.b/(c)"), rule));
    assertFalse(matcher.matches(new ToolPermissionKey("Read", "/aXb/(c)"), rule));
  }

  @Test
  void emptyCanonicalArgsMatchAnyArgsRule() {
    var rule = PermissionRule.any(PermissionEffect.ALLOW, "Execute");
    var key = new ToolPermissionKey("Execute", "");
    assertTrue(matcher.matches(key, rule));
  }

  @Test
  void matchesRejectsNullKey() {
    var rule = PermissionRule.any(PermissionEffect.ALLOW, "Read");
    assertThrows(NullPointerException.class, () -> matcher.matches(null, rule));
  }

  @Test
  void matchesRejectsNullRule() {
    var key = ToolPermissionKey.of("Read");
    assertThrows(NullPointerException.class, () -> matcher.matches(key, null));
  }

  // ── firstMatch() ─────────────────────────────────────────────────────────

  @Test
  void firstMatchReturnsEarliestRule() {
    var first = PermissionRule.any(PermissionEffect.ALLOW, "Read");
    var second = PermissionRule.any(PermissionEffect.DENY, "Read");
    var match = matcher.firstMatch(ToolPermissionKey.of("Read"), List.of(first, second));
    assertEquals(first, match.orElseThrow());
  }

  @Test
  void firstMatchReturnsEmptyForNoMatch() {
    var rules = List.of(PermissionRule.any(PermissionEffect.ALLOW, "Read"));
    assertTrue(matcher.firstMatch(ToolPermissionKey.of("Write"), rules).isEmpty());
  }

  @Test
  void firstMatchSkipsMismatchingPattern() {
    var skip = PermissionRule.withGlob(PermissionEffect.DENY, "Read", "/private/**");
    var hit = PermissionRule.withGlob(PermissionEffect.ALLOW, "Read", "/workspace/**");
    var match =
        matcher.firstMatch(new ToolPermissionKey("Read", "/workspace/foo.txt"), List.of(skip, hit));
    assertEquals(hit, match.orElseThrow());
  }

  @Test
  void firstMatchRejectsNullKey() {
    assertThrows(NullPointerException.class, () -> matcher.firstMatch(null, List.of()));
  }

  @Test
  void firstMatchRejectsNullRules() {
    assertThrows(
        NullPointerException.class, () -> matcher.firstMatch(ToolPermissionKey.of("Read"), null));
  }
}
