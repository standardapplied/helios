/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.session.tools.ToolPermissionKey;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class RuleMatcherTest {

  private final RuleMatcher matcher = new RuleMatcher();

  // ── matches() basics ─────────────────────────────────────────────────────

  @Test
  void differentToolNameDoesNotMatch() {
    var rule = PermissionRule.any("Read");
    var key = ToolPermissionKey.of("Write");
    assertFalse(matcher.matches(key, rule));
  }

  @Test
  void sameToolNameWithAbsentPatternMatches() {
    var rule = PermissionRule.any("Read");
    var key = new ToolPermissionKey("Read", "/anything");
    assertTrue(matcher.matches(key, rule));
  }

  @Test
  void emptyCanonicalArgsMatchAnyArgsRule() {
    var rule = PermissionRule.any("Execute");
    var key = new ToolPermissionKey("Execute", "");
    assertTrue(matcher.matches(key, rule));
  }

  @Test
  void matchesRejectsNullKey() {
    var rule = PermissionRule.any("Read");
    assertThrows(NullPointerException.class, () -> matcher.matches(null, rule));
  }

  @Test
  void matchesRejectsNullRule() {
    var key = ToolPermissionKey.of("Read");
    assertThrows(NullPointerException.class, () -> matcher.matches(key, null));
  }

  // ── the glob dialect over canonical paths ────────────────────────────────

  private boolean globMatches(String glob, String canonical) {
    return matcher.matches(
        new ToolPermissionKey("Read", canonical), PermissionRule.of("Read", glob));
  }

  @ParameterizedTest(name = "{0} matches {1}: {2}")
  @CsvSource({
    "./**, ., true",
    "./**, ./a/b, true",
    "./**, /abs/x, false",
    "./secrets/**, ./secrets, true",
    "./secrets/**, ./secrets/k, true",
    "./secrets/**, ./secrets-old/k, false",
    "./**/secrets/**, ./secrets/k, true",
    "./**/secrets/**, ./a/secrets/k, true",
    "./**/secrets/**, ./a/secretsx/k, false",
    "**/secrets.*, ./secrets.env, true",
    "**/secrets.*, ./a/secrets.env, true",
    "**/secrets.*, ./secrets.env/x, false",
    "./*, ./a, true",
    "./*, ./a/b, false"
  })
  void theGlobDialectMatchesCanonicalPaths(String glob, String canonical, boolean matches) {
    assertEquals(matches, globMatches(glob, canonical));
  }

  @ParameterizedTest(name = "{0} matches {1}: {2}")
  @CsvSource({
    "./secrets/**, ./secrets/, true",
    "./secrets/**, ./secretsx, false",
    "./secrets/**, ./x/secrets, false",
    "./**/k, ./k, true",
    "./**/k, ./a/b/k, true",
    "./**/k, ./ak, false",
    "**/k, k, true",
    "**/k, ./a/k, true",
    "**/k, ./ak, false",
    "./a**b, ./a/x/b, true",
    "./a**b, ./ab, true",
    "./a**b, ./a/x/c, false",
    "./a/**x, ./a/y/zx, true",
    "./a/**x, ./a, false",
    "**, ., true",
    "./?, ./a, true",
    "./?, ./ab, false",
    "./?, ./, false",
    "./[a], ./[a], true",
    "./[a], ./a, false",
    "'./{a,b}', './{a,b}', true",
    "./a+b, ./a+b, true",
    "./a+b, ./aab, false",
    "./a.b, ./aXb, false",
    "./a/**x, ./ax, false",
    "./**/**/secrets/**, ./secrets/k, true",
    "./**/**/secrets/**, ./a/b/secrets/k, true",
    "./**/**/secrets/**, ./a/secretsx/k, false",
    "./a/**/**, ./a, true",
    "./a/**/**, ./a/b/c, true",
    "./a/**/**, ./ab, false",
    "**/**/k, k, true",
    "**/**/k, ./a/b/k, true",
    "**/**/k, ./ak, false",
    "./🔒/k, ./🔒/k, true",
    "./🔒/**, ./🔒/k, true",
    "./?, ./🔒, true",
    "/workspace/foo.txt, /workspace/foo.txt, true",
    "/workspace/*, /workspace/foo.txt, true",
    "/workspace/*, /workspace/sub/foo.txt, false",
    "/workspace/**, /workspace/foo.txt, true",
    "/workspace/**, /workspace/sub/deep/foo.txt, true",
    "/a/?.txt, /a/x.txt, true",
    "/a/?.txt, /a/xy.txt, false",
    "/a.b/(c), /a.b/(c), true",
    "/a.b/(c), /aXb/(c), false"
  })
  void wildcardsAndLiteralsFollowTheDialect(String glob, String canonical, boolean matches) {
    assertEquals(matches, globMatches(glob, canonical));
  }

  @Test
  void aWildcardMatchesALineTerminator() {
    assertTrue(globMatches("./**", "./a\nb"));
  }

  @Test
  void aGlobMatchesTheSameKeysEveryTimeItIsUsed() {
    assertTrue(globMatches("./cached/**", "./cached/a"));
    assertTrue(globMatches("./cached/**", "./cached/a"));
    assertFalse(globMatches("./cached/**", "./other"));
  }

  // ── firstMatch() ─────────────────────────────────────────────────────────

  @Test
  void firstMatchReturnsEarliestRule() {
    var first = PermissionRule.any("Read");
    var second = PermissionRule.any("Read");
    var match = matcher.firstMatch(ToolPermissionKey.of("Read"), List.of(first, second));
    assertEquals(first, match.orElseThrow());
  }

  @Test
  void firstMatchReturnsEmptyForNoMatch() {
    var rules = List.of(PermissionRule.any("Read"));
    assertTrue(matcher.firstMatch(ToolPermissionKey.of("Write"), rules).isEmpty());
  }

  @Test
  void firstMatchSkipsMismatchingPattern() {
    var skip = PermissionRule.of("Read", "/private/**");
    var hit = PermissionRule.of("Read", "/workspace/**");
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
