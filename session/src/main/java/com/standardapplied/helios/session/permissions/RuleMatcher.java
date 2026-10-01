/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.permissions;

import ai.singlr.session.tools.ToolPermissionKey;
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
 * <p>Glob syntax: {@code *} matches a single path segment, {@code **} matches any number of
 * segments (including zero), {@code ?} matches a single non-slash character, all other characters
 * are literal. This is the standard subset of {@code java.nio.file.PathMatcher} glob syntax; we
 * re-implement here to avoid the {@code Path}-based matcher's filesystem dependence — the canonical
 * args we match against are plain strings, not real paths.
 *
 * <h2>Thread-safety</h2>
 *
 * Stateless. Safe to share.
 */
public final class RuleMatcher {

  private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

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

  /**
   * Convert a glob to a regex and test. Visible package-private so {@link
   * DefaultPermissionEvaluator} can re-use the same matching when computing the diagnostic reason.
   */
  static boolean globMatches(String glob, String input) {
    return PATTERN_CACHE
        .computeIfAbsent(glob, g -> Pattern.compile(globToRegex(g)))
        .matcher(input)
        .matches();
  }

  /**
   * Convert a glob pattern to a regex. Supports {@code *}, {@code **}, {@code ?}; escapes other
   * regex meta-characters.
   */
  static String globToRegex(String glob) {
    var out = new StringBuilder();
    out.append('^');
    int i = 0;
    while (i < glob.length()) {
      char c = glob.charAt(i);
      if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
        out.append(".*");
        i += 2;
      } else if (c == '*') {
        out.append("[^/]*");
        i++;
      } else if (c == '?') {
        out.append("[^/]");
        i++;
      } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
        out.append('\\').append(c);
        i++;
      } else {
        out.append(c);
        i++;
      }
    }
    out.append('$');
    return out.toString();
  }
}
