/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.runtime.AuthenticationGate.Caller;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which challenges a gate accepts, and how long an admitted caller's identity lasts: until its
 * lifetime, revocation or release.
 */
final class AuthenticationGateTest {

  private static final Principal ALICE = new Principal("acme", "alice");

  /**
   * A thread stack a quarter of the JVM's default, too small for a regex that recurses per
   * character.
   */
  private static final long SMALL_STACK_BYTES = 256 * 1024;

  private static final AuthenticationGate GATE =
      new AuthenticationGate(new TestAuthenticator(request -> Optional.empty()));

  @Test
  void aGateRefusesANullAuthenticator() {
    var ex = assertThrows(NullPointerException.class, () -> new AuthenticationGate(null));
    assertEquals("authenticator must not be null", ex.getMessage());
  }

  /** Each challenge a gate refuses, with the message naming the rule it breaks. */
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
            "Bearer " + "a".repeat(AuthenticationGate.MAX_CHALLENGE_LENGTH - 6),
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
   * Challenges a gate accepts: a bare scheme, token68s of every token68 character and padding, and
   * lists of auth-params, with and without spaces around their separators, one with the highest
   * printable character, one with a quoted value that looks like a second, repeated parameter and
   * one with a token value that is not a realm.
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
        "Bearer " + "a".repeat(AuthenticationGate.MAX_CHALLENGE_LENGTH - 7));
  }

  @ParameterizedTest
  @MethodSource("invalidChallenges")
  void aServiceWhoseAuthenticatorHasAnInvalidChallengeIsRefused(String challenge, String message) {
    var ex = assertThrows(IllegalArgumentException.class, () -> service(challenge));
    assertEquals(message, ex.getMessage());
  }

  @ParameterizedTest
  @MethodSource("validChallenges")
  void aServiceAcceptsAValidChallenge(String challenge) {
    assertDoesNotThrow(() -> service(challenge));
  }

  /**
   * Longest challenges whose every character is a repetition of a quoted-string's content or of the
   * auth-param list: a realm of quoted-pairs and as many parameters as fit.
   */
  static Stream<String> longestRepetitiveChallenges() {
    var quotedPairs = new StringBuilder("Bearer realm=\"");
    while (quotedPairs.length() + 3 <= AuthenticationGate.MAX_CHALLENGE_LENGTH) {
      quotedPairs.append("\\\"");
    }
    var parameters = new StringBuilder("Bearer realm=\"\"");
    for (var i = 0; parameters.length() + 8 <= AuthenticationGate.MAX_CHALLENGE_LENGTH; i++) {
      parameters.append(String.format(",p%03d=\"\"", i));
    }
    return Stream.of(quotedPairs.append('"').toString(), parameters.toString());
  }

  @ParameterizedTest
  @MethodSource("longestRepetitiveChallenges")
  void aLongestChallengeIsAcceptedOnAThreadWithASmallStack(String challenge) {
    var validation = new FutureTask<>(() -> AuthenticationGate.requireValidChallenge(challenge));
    Thread.ofPlatform().stackSize(SMALL_STACK_BYTES).start(validation);

    assertEquals(challenge, Await.value("the challenge's validation", validation));
  }

  @Test
  void aServiceWhoseAuthenticatorHasNoChallengeIsRefused() {
    var ex = assertThrows(NullPointerException.class, () -> service(null));
    assertEquals("challenge must not be null", ex.getMessage());
  }

  private static AgentHttpService service(String challenge) {
    return new AgentHttpService(
        SessionRegistry.inMemory(),
        request -> {
          throw new AssertionError("no session is created");
        },
        new TestAuthenticator(request -> Optional.empty(), challenge),
        JsonMapper.builder().build(),
        "/v1",
        RequestLimits.defaults());
  }

  @Test
  void aLiveIdentityStaysLiveUntilTheRequestReleasesIt() {
    var caller = Caller.start(Authentication.of(ALICE), GATE);
    assertEquals(ALICE, caller.principal());
    assertFalse(caller.ended().isDone());

    caller.release();

    assertTrue(caller.ended().isDone());
  }

  @Test
  void theIdentityEndsWhenItsLifetimeElapses() {
    var caller =
        Caller.start(
            new Authentication(ALICE, Duration.ofMillis(1), new CompletableFuture<>()), GATE);

    assertNull(Await.value("the identity's lifetime to elapse", caller.ended()));
  }

  @Test
  void anIdentityWithNoLifetimeLeftHasEndedAtStart() {
    var caller =
        Caller.start(
            new Authentication(ALICE, Duration.ofSeconds(-1), new CompletableFuture<>()), GATE);

    assertTrue(caller.ended().isDone());
  }

  @Test
  void anIdentityWithAZeroLifetimeHasEndedAtStart() {
    var caller =
        Caller.start(new Authentication(ALICE, Duration.ZERO, new CompletableFuture<>()), GATE);

    assertTrue(caller.ended().isDone());
  }

  @Test
  void theIdentityEndsWhenItsCredentialIsRevoked() {
    var revoked = new CompletableFuture<Void>();
    var caller = Caller.start(new Authentication(ALICE, Duration.ofDays(1), revoked), GATE);

    revoked.complete(null);

    assertTrue(caller.ended().isDone());
    assertFalse(caller.ended().isCompletedExceptionally());
  }

  @Test
  void aRevocationThatFailsAlsoEndsTheIdentity() {
    var revoked = new CompletableFuture<Void>();
    var caller = Caller.start(new Authentication(ALICE, Duration.ofDays(1), revoked), GATE);

    revoked.completeExceptionally(new IllegalStateException("revocation feed lost"));

    assertTrue(caller.ended().isDone());
    assertFalse(caller.ended().isCompletedExceptionally());
  }

  @Test
  void releasingTheCallerLeavesTheVerifiersStageUntouched() {
    var revoked = new CompletableFuture<Void>();
    var caller = Caller.start(new Authentication(ALICE, Duration.ofDays(1), revoked), GATE);

    caller.release();

    assertFalse(revoked.isDone());
  }

  @Test
  void requestsThatEndedLeaveNothingWatchingASharedRevocationStage() {
    var revoked = new CompletableFuture<Void>();
    var authentication = new Authentication(ALICE, Duration.ofDays(1), revoked);
    var expiring = new Authentication(ALICE, Duration.ofMillis(1), revoked);

    for (var i = 0; i < 1_000; i++) {
      Caller.start(authentication, GATE).release();
    }
    Await.value("the identity's lifetime to elapse", Caller.start(expiring, GATE).ended());

    Await.until(
        "the elapsed identity to stop watching the revocation stage",
        () -> revoked.getNumberOfDependents() == 0);
  }

  @Test
  void freshAuthenticationsSharingOneRevocationFutureLeaveNothingWatchingIt() {
    var revoked = new CompletableFuture<Void>();

    for (var i = 0; i < 1_000; i++) {
      Caller.start(new Authentication(ALICE, Duration.ofDays(1), revoked), GATE).release();
    }

    assertEquals(0, revoked.getNumberOfDependents());
  }

  @Test
  void aMinimalStageConvertedOnceByTheVerifierKeepsOneCallbackHoweverManyRequestsUseIt() {
    var source = new CompletableFuture<Void>();
    var revoked = source.minimalCompletionStage().toCompletableFuture();
    var callers = new ArrayList<Caller>();

    for (var i = 0; i < 1_000; i++) {
      var caller = Caller.start(new Authentication(ALICE, Duration.ofDays(1), revoked), GATE);
      callers.add(caller);
      if (i % 2 == 0) {
        caller.release();
      }
    }
    assertEquals(1, source.getNumberOfDependents());
    source.complete(null);

    assertTrue(callers.stream().allMatch(caller -> caller.ended().isDone()));
    assertEquals(0, revoked.getNumberOfDependents());
  }

  @Test
  void anAuthenticationOfAPrincipalNeitherExpiresNorIsRevoked() {
    var authentication = Authentication.of(new Principal("acme", "alice"));
    assertEquals(ChronoUnit.FOREVER.getDuration(), authentication.lifetime());
    assertFalse(authentication.revoked().isDone());
  }

  @Test
  void anAuthenticationRefusesNullComponents() {
    var alice = new Principal("acme", "alice");
    var never = new CompletableFuture<Void>();
    assertEquals(
        "principal must not be null",
        assertThrows(
                NullPointerException.class, () -> new Authentication(null, Duration.ZERO, never))
            .getMessage());
    assertEquals(
        "lifetime must not be null",
        assertThrows(NullPointerException.class, () -> new Authentication(alice, null, never))
            .getMessage());
    assertEquals(
        "revoked must not be null",
        assertThrows(
                NullPointerException.class, () -> new Authentication(alice, Duration.ZERO, null))
            .getMessage());
  }
}
