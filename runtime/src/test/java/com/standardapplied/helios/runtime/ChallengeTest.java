/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.test.Await;
import java.util.concurrent.FutureTask;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Which {@code WWW-Authenticate} values are a valid {@link Challenge}. The cases are shared: a
 * service and {@link TrustedProxyAuthenticator.Builder#build()} refuse and accept exactly these.
 */
final class ChallengeTest {

  /**
   * A thread stack a quarter of the JVM's default, too small for a regex that recurses per
   * character.
   */
  private static final long SMALL_STACK_BYTES = 256 * 1024;

  /** Each challenge the validator refuses, with the message naming the rule it breaks. */
  static Stream<Arguments> invalidChallenges() {
    var notPrintable = "challenge must be printable ASCII (0x20-0x7E), got ";
    var notFollowedByParameters =
        "challenge's auth-scheme must be followed by one space and a token68 or a"
            + " comma-separated list of auth-params, got ";
    var repeatedName = "challenge's auth-param names must be unique, ignoring case, but ";
    var unquotedRealm = "challenge's realm must be a quoted-string, got ";
    return Stream.of(
        Arguments.of("", "challenge must be non-blank"),
        Arguments.of(" \t", "challenge must be non-blank"),
        Arguments.of("Bearer realm=\"a\"\r\nX-Injected: 1", notPrintable + "0x000D at index 16"),
        Arguments.of("Bearer realm=\"a\"\nX-Injected: 1", notPrintable + "0x000A at index 16"),
        Arguments.of("Bearer realm=\"a\u001F\"", notPrintable + "0x001F at index 15"),
        Arguments.of("Bearer realm=\"a\u007F\"", notPrintable + "0x007F at index 15"),
        Arguments.of("Bearer realm=\"h\u00e9lios\"", notPrintable + "0x00E9 at index 15"),
        Arguments.of(
            "Bear/er realm=\"a\"",
            "challenge must begin with an auth-scheme token, got 'Bear/er realm=\"a\"'"),
        Arguments.of(" Bearer", "challenge must begin with an auth-scheme token, got ' Bearer'"),
        Arguments.of(
            "Bearer " + "a".repeat(Challenge.MAX_LENGTH - 6),
            "challenge must be at most 1024 characters, got 1025"),
        Arguments.of("Bearer  realm=\"a\"", notFollowedByParameters + "'Bearer  realm=\"a\"'"),
        Arguments.of("Bearer realm=\"a\",", notFollowedByParameters + "'Bearer realm=\"a\",'"),
        Arguments.of("Bearer realm=\"a", notFollowedByParameters + "'Bearer realm=\"a'"),
        Arguments.of("Bearer realm=a b", notFollowedByParameters + "'Bearer realm=a b'"),
        Arguments.of(
            "Bearer realm=\"one\", realm=\"two\"",
            repeatedName + "'realm' repeats in 'Bearer realm=\"one\", realm=\"two\"'"),
        Arguments.of(
            "Bearer realm=\"one\", scope=read, REALM=\"two\"",
            repeatedName + "'REALM' repeats in 'Bearer realm=\"one\", scope=read, REALM=\"two\"'"),
        Arguments.of("Basic realm=helios", unquotedRealm + "'Basic realm=helios'"),
        Arguments.of("Basic REALM = helios", unquotedRealm + "'Basic REALM = helios'"),
        Arguments.of(
            "Bearer scope=\"realm=x\", Realm=helios",
            unquotedRealm + "'Bearer scope=\"realm=x\", Realm=helios'"));
  }

  /**
   * Challenges the validator accepts: a bare scheme, token68s of every token68 character and
   * padding, and lists of auth-params, with and without spaces around their separators, one with
   * the highest printable character, one with a quoted value that looks like a second, repeated
   * parameter and one with a token value that is not a realm.
   */
  static Stream<String> validChallenges() {
    return Stream.of(
        "Negotiate",
        "Bearer dG9rZW4=",
        "Bearer AZaz09-._~+/==",
        "Bearer realm=\"~\",scope=read",
        "Bearer realm=\"helios\", error=\"invalid_token\", scope=read",
        "Bearer realm = \"a \\\"quoted\\\" realm\"",
        "Bearer realm=\"one, realm=two\", scope=read",
        "Bearer scope=realm, realms=read",
        "Bearer " + "a".repeat(Challenge.MAX_LENGTH - 7));
  }

  /**
   * Longest challenges whose every character is a repetition of a quoted-string's content or of the
   * auth-param list: a realm of quoted-pairs and as many parameters as fit.
   */
  static Stream<String> longestRepetitiveChallenges() {
    var quotedPairs = new StringBuilder("Bearer realm=\"");
    while (quotedPairs.length() + 3 <= Challenge.MAX_LENGTH) {
      quotedPairs.append("\\\"");
    }
    var parameters = new StringBuilder("Bearer realm=\"\"");
    for (var i = 0; parameters.length() + 8 <= Challenge.MAX_LENGTH; i++) {
      parameters.append(String.format(",p%03d=\"\"", i));
    }
    return Stream.of(quotedPairs.append('"').toString(), parameters.toString());
  }

  @ParameterizedTest
  @MethodSource("longestRepetitiveChallenges")
  void aLongestChallengeIsAcceptedOnAThreadWithASmallStack(String challenge) {
    var validation = new FutureTask<>(() -> new Challenge(challenge).value());
    Thread.ofPlatform().stackSize(SMALL_STACK_BYTES).start(validation);

    assertEquals(challenge, Await.value("the challenge's validation", validation));
  }

  @ParameterizedTest
  @MethodSource("invalidChallenges")
  void anInvalidChallengeIsRefusedNamingTheRuleItBreaks(String challenge, String message) {
    var ex = assertThrows(IllegalArgumentException.class, () -> new Challenge(challenge));
    assertEquals(message, ex.getMessage());
  }

  @ParameterizedTest
  @MethodSource("validChallenges")
  void aValidChallengeIsKeptAsSent(String challenge) {
    assertEquals(challenge, new Challenge(challenge).value());
  }

  @Test
  void aNullChallengeIsRefused() {
    var ex = assertThrows(NullPointerException.class, () -> new Challenge(null));
    assertEquals("challenge must not be null", ex.getMessage());
  }
}
