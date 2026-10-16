/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The syntax of a {@code WWW-Authenticate} value: one RFC 9110 §11 challenge, an auth-scheme token
 * optionally followed by one space and a token68 or a comma-separated list of auth-params whose
 * names are unique ignoring case and whose realm, if any, is a quoted-string, in printable ASCII,
 * non-blank and at most {@value #MAX_LENGTH} characters.
 */
final class Challenge {

  /** Longest accepted challenge, in characters. */
  static final int MAX_LENGTH = 1024;

  private static final String TOKEN = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";

  /**
   * Group repetitions are possessive: a backtracking one recurses once per repetition and overflows
   * a small thread stack on a {@value #MAX_LENGTH}-character challenge. No repetition consumes
   * anything the rest of the pattern could match, so the pattern accepts the same challenges as a
   * backtracking one.
   */
  private static final String QUOTED_STRING = "\"(?:[ !#-\\[\\]-~]|\\\\[ -~])*+\"";

  private static final String AUTH_PARAM =
      "(" + TOKEN + ") *= *(" + TOKEN + "|" + QUOTED_STRING + ")";

  private static final Pattern AUTH_SCHEME = Pattern.compile(TOKEN);

  private static final Pattern AUTH_PARAM_PATTERN = Pattern.compile(AUTH_PARAM);

  /** Repeats its auth-params possessively, for the reason {@link #QUOTED_STRING} gives. */
  private static final Pattern TOKEN68_OR_AUTH_PARAMS =
      Pattern.compile("[0-9A-Za-z._~+/-]+=*|" + AUTH_PARAM + "(?: *, *" + AUTH_PARAM + ")*+");

  private Challenge() {}

  /**
   * Return {@code challenge} if it is a valid challenge.
   *
   * @param challenge the candidate {@code WWW-Authenticate} value
   * @return {@code challenge}
   * @throws NullPointerException if {@code challenge} is null
   * @throws IllegalArgumentException naming the rule {@code challenge} breaks
   */
  static String requireValid(String challenge) {
    Objects.requireNonNull(challenge, "challenge must not be null");
    if (challenge.isBlank()) {
      throw new IllegalArgumentException("challenge must be non-blank");
    }
    if (challenge.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(
          "challenge must be at most " + MAX_LENGTH + " characters, got " + challenge.length());
    }
    requirePrintableAscii(challenge);
    requireSyntax(challenge);
    return challenge;
  }

  private static void requirePrintableAscii(String challenge) {
    for (var i = 0; i < challenge.length(); i++) {
      var c = challenge.charAt(i);
      if (c < 0x20 || c > 0x7E) {
        throw new IllegalArgumentException(
            String.format(
                "challenge must be printable ASCII (0x20-0x7E), got 0x%04X at index %d",
                (int) c, i));
      }
    }
  }

  private static void requireSyntax(String challenge) {
    var space = challenge.indexOf(' ');
    var scheme = space < 0 ? challenge : challenge.substring(0, space);
    if (!AUTH_SCHEME.matcher(scheme).matches()) {
      throw new IllegalArgumentException(
          "challenge must begin with an auth-scheme token, got '" + challenge + "'");
    }
    if (space < 0) {
      return;
    }
    var credentials = challenge.substring(space + 1);
    if (!TOKEN68_OR_AUTH_PARAMS.matcher(credentials).matches()) {
      throw new IllegalArgumentException(
          "challenge's auth-scheme must be followed by one space and a token68 or a"
              + " comma-separated list of auth-params, got '"
              + challenge
              + "'");
    }
    requireValidParameters(challenge, credentials);
  }

  /**
   * RFC 9110 §11.2: each auth-param name occurs once per challenge, compared case-insensitively;
   * §11.5: a sender generates {@code realm} only as a quoted-string.
   */
  private static void requireValidParameters(String challenge, String credentials) {
    var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
    var parameters = AUTH_PARAM_PATTERN.matcher(credentials);
    while (parameters.find()) {
      var name = parameters.group(1);
      if (name.equalsIgnoreCase("realm") && !parameters.group(2).startsWith("\"")) {
        throw new IllegalArgumentException(
            "challenge's realm must be a quoted-string, got '" + challenge + "'");
      }
      if (!names.add(name)) {
        throw new IllegalArgumentException(
            "challenge's auth-param names must be unique, ignoring case, but '"
                + name
                + "' repeats in '"
                + challenge
                + "'");
      }
    }
  }
}
