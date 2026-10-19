/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.permissions;

import com.standardapplied.helios.session.tools.ToolPermissionKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Match a {@link ToolPermissionKey} against a list of {@link PermissionRule}s.
 *
 * <p>A rule matches when (a) its {@code toolName} equals the key's tool name AND (b) either its
 * {@code argPattern} is absent OR the pattern's glob matches the key's canonical args. The first
 * matching rule wins.
 *
 * <p>A path tool's key is its canonical path ({@code .} or {@code ./a/b} in a workspace, {@code
 * /memories/} or {@code /memories/a/b} in memory), so a glob is written in that spelling. Glob
 * syntax:
 *
 * <ul>
 *   <li>a trailing {@code /**} matches the path before it and everything below it: {@code ./**}
 *       matches {@code .}, and {@code ./secrets/**} matches {@code ./secrets} and {@code
 *       ./secrets/key};
 *   <li>{@code /**}{@code /} and a leading {@code **}{@code /} match zero or more whole segments:
 *       {@code ./**}{@code /secrets/**} matches {@code ./secrets/key} and {@code ./a/secrets/key};
 *   <li>any other {@code **} matches any characters, {@code /} included;
 *   <li>{@code *} matches any characters but {@code /}, and {@code ?} one such character;
 *   <li>every other character, brackets and braces included, is literal.
 * </ul>
 *
 * <p>A wildcard matches line terminators like any other character. The matching works on plain
 * strings, not on the filesystem.
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe: its only state is a concurrent cache of compiled globs. Safe to share.
 */
public final class RuleMatcher {

  private final ConcurrentHashMap<String, Pattern> patterns = new ConcurrentHashMap<>();

  /** Default constructor. */
  public RuleMatcher() {}

  /**
   * Find the first rule in {@code rules} that matches {@code key}.
   *
   * @param key the tool permission key; non-null
   * @param rules the rules to scan, in declaration order; non-null
   * @return the first matching rule, or empty
   * @throws NullPointerException if either argument is null
   */
  public Optional<PermissionRule> firstMatch(ToolPermissionKey key, List<PermissionRule> rules) {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(rules, "rules must not be null");
    for (var rule : rules) {
      if (matches(key, rule)) {
        return Optional.of(rule);
      }
    }
    return Optional.empty();
  }

  /**
   * Test whether {@code rule} matches {@code key}.
   *
   * @param key the tool permission key; non-null
   * @param rule the rule; non-null
   * @return {@code true} if the rule matches
   * @throws NullPointerException if either argument is null
   */
  public boolean matches(ToolPermissionKey key, PermissionRule rule) {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(rule, "rule must not be null");
    if (!key.toolName().equals(rule.toolName())) {
      return false;
    }
    if (rule.argPattern().isEmpty()) {
      return true;
    }
    return globMatches(rule.argPattern().orElseThrow(), key.canonicalArgs());
  }

  /** Whether {@code glob} matches {@code input}, compiling each glob once per matcher. */
  private boolean globMatches(String glob, String input) {
    return patterns
        .computeIfAbsent(glob, g -> Pattern.compile(globToRegex(g), Pattern.DOTALL))
        .matcher(input)
        .matches();
  }

  /**
   * Convert a glob to a regex: {@code /**} ending a segment and a leading {@code **}{@code /} match
   * zero or more whole segments, any other {@code **} matches any characters, {@code *} and {@code
   * ?} match within a segment, and every other code point is literal.
   */
  private static String globToRegex(String glob) {
    var out = new StringBuilder();
    var i = 0;
    while (glob.startsWith("**/", i)) {
      out.append("(.*/)?");
      i += 3;
    }
    while (i < glob.length()) {
      if (glob.startsWith("/**", i) && endsSegment(glob, i + 3)) {
        out.append("(/.*)?");
        i += 3;
      } else if (glob.startsWith("**", i)) {
        out.append(".*");
        i += 2;
      } else {
        var codePoint = glob.codePointAt(i);
        out.append(literalOrWildcard(codePoint));
        i += Character.charCount(codePoint);
      }
    }
    return out.toString();
  }

  private static boolean endsSegment(String glob, int index) {
    return index == glob.length() || glob.charAt(index) == '/';
  }

  private static String literalOrWildcard(int codePoint) {
    return switch (codePoint) {
      case '*' -> "[^/]*";
      case '?' -> "[^/]";
      default -> Pattern.quote(Character.toString(codePoint));
    };
  }
}
