/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.permissions;

import com.standardapplied.helios.session.tools.ToolPermissionKey;
import java.util.List;
import java.util.Optional;
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
 * strings, not on the filesystem, so a glob names real paths: one written through a symlink, such
 * as {@code ./link/**}, never matches, since a canonical path never goes through one.
 *
 * <p>A glob is compiled only for a rule naming the key's tool. Stateless.
 */
final class RuleMatcher {

  private RuleMatcher() {}

  /** The first rule in {@code rules}, in declaration order, that matches {@code key}. */
  static Optional<PermissionRule> firstMatch(ToolPermissionKey key, List<PermissionRule> rules) {
    for (var rule : rules) {
      if (matches(key, rule)) {
        return Optional.of(rule);
      }
    }
    return Optional.empty();
  }

  /** Whether {@code rule} matches {@code key}. */
  static boolean matches(ToolPermissionKey key, PermissionRule rule) {
    return key.toolName().equals(rule.toolName())
        && rule.argPattern().map(glob -> globMatches(glob, key.canonicalArgs())).orElse(true);
  }

  private static boolean globMatches(String glob, String input) {
    return Pattern.compile(globToRegex(glob), Pattern.DOTALL).matcher(input).matches();
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
