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
import io.helidon.webserver.http.ServerRequest;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A gate refuses an authenticator whose challenge is invalid and sends the one it validated, and an
 * admitted caller's identity lasts until its lifetime, revocation or release.
 */
final class AuthenticationGateTest {

  private static final Principal ALICE = new Principal("acme", "alice");

  private static final Challenge CHALLENGE = new Challenge(TestAuthenticator.CHALLENGE);

  @Test
  void aGateRefusesANullAuthenticator() {
    var ex = assertThrows(NullPointerException.class, () -> new AuthenticationGate(null));
    assertEquals("authenticator must not be null", ex.getMessage());
  }

  @ParameterizedTest
  @MethodSource("com.standardapplied.helios.runtime.ChallengeTest#invalidChallenges")
  void aServiceWhoseAuthenticatorHasAnInvalidChallengeIsRefused(String challenge, String message) {
    var ex = assertThrows(IllegalArgumentException.class, () -> service(challenge));
    assertEquals(message, ex.getMessage());
  }

  @ParameterizedTest
  @MethodSource("com.standardapplied.helios.runtime.ChallengeTest#validChallenges")
  void aServiceAcceptsAValidChallenge(String challenge) {
    assertDoesNotThrow(() -> service(challenge));
  }

  @Test
  void aServiceWhoseAuthenticatorHasNoChallengeIsRefused() {
    var ex = assertThrows(NullPointerException.class, () -> service(null));
    assertEquals("challenge must not be null", ex.getMessage());
  }

  @Test
  void aDenialCarriesTheChallengeTheGateValidatedWhateverTheAuthenticatorReturnsLater()
      throws Exception {
    var reads = new AtomicInteger();
    var changing =
        new Authenticator() {
          @Override
          public Optional<Authentication> authenticate(ServerRequest request) {
            return Optional.empty();
          }

          @Override
          public String challenge() {
            return reads.getAndIncrement() == 0
                ? TestAuthenticator.CHALLENGE
                : "Bearer realm=\"unvalidated\"";
          }
        };
    try (var server =
            RuntimeServer.builder()
                .withRegistry(SessionRegistry.inMemory())
                .withOptionsFactory(
                    request -> {
                      throw new AssertionError("no session is created");
                    })
                .withAuthenticator(changing)
                .build();
        var connection = RawConnection.open(server.port())) {
      connection.write(
          "POST /v1/sessions HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n");

      var denial = connection.response();

      assertEquals("HTTP/1.1 401 Unauthorized", denial.statusLine());
      assertEquals(TestAuthenticator.CHALLENGE, denial.headers().get("www-authenticate"));
      assertEquals(1, reads.get());
    }
  }

  private static AgentHttpService service(String challenge) {
    return new AgentHttpService(
        SessionRegistry.inMemory(),
        request -> {
          throw new AssertionError("no session is created");
        },
        new TestAuthenticator(request -> Optional.empty(), challenge),
        "/v1",
        RequestLimits.defaults());
  }

  @Test
  void aLiveIdentityStaysLiveUntilTheRequestReleasesIt() {
    var caller = Caller.start(Authentication.of(ALICE), CHALLENGE);
    assertEquals(ALICE, caller.principal());
    assertFalse(caller.ended().isDone());

    caller.release();

    assertTrue(caller.ended().isDone());
  }

  @Test
  void theIdentityEndsWhenItsLifetimeElapses() {
    var caller =
        Caller.start(
            new Authentication(ALICE, Duration.ofMillis(1), new CompletableFuture<>()), CHALLENGE);

    assertNull(Await.value("the identity's lifetime to elapse", caller.ended()));
  }

  @Test
  void anIdentityWithNoLifetimeLeftHasEndedAtStart() {
    var caller =
        Caller.start(
            new Authentication(ALICE, Duration.ofSeconds(-1), new CompletableFuture<>()),
            CHALLENGE);

    assertTrue(caller.ended().isDone());
  }

  @Test
  void anIdentityWithAZeroLifetimeHasEndedAtStart() {
    var caller =
        Caller.start(
            new Authentication(ALICE, Duration.ZERO, new CompletableFuture<>()), CHALLENGE);

    assertTrue(caller.ended().isDone());
  }

  @Test
  void theIdentityEndsWhenItsCredentialIsRevoked() {
    var revoked = new CompletableFuture<Void>();
    var caller = Caller.start(new Authentication(ALICE, Duration.ofDays(1), revoked), CHALLENGE);

    revoked.complete(null);

    assertTrue(caller.ended().isDone());
    assertFalse(caller.ended().isCompletedExceptionally());
  }

  @Test
  void aRevocationThatFailsAlsoEndsTheIdentity() {
    var revoked = new CompletableFuture<Void>();
    var caller = Caller.start(new Authentication(ALICE, Duration.ofDays(1), revoked), CHALLENGE);

    revoked.completeExceptionally(new IllegalStateException("revocation feed lost"));

    assertTrue(caller.ended().isDone());
    assertFalse(caller.ended().isCompletedExceptionally());
  }

  @Test
  void releasingTheCallerLeavesTheVerifiersStageUntouched() {
    var revoked = new CompletableFuture<Void>();
    var caller = Caller.start(new Authentication(ALICE, Duration.ofDays(1), revoked), CHALLENGE);

    caller.release();

    assertFalse(revoked.isDone());
  }

  @Test
  void requestsThatEndedLeaveNothingWatchingASharedRevocationStage() {
    var revoked = new CompletableFuture<Void>();
    var authentication = new Authentication(ALICE, Duration.ofDays(1), revoked);
    var expiring = new Authentication(ALICE, Duration.ofMillis(1), revoked);

    for (var i = 0; i < 1_000; i++) {
      Caller.start(authentication, CHALLENGE).release();
    }
    Await.value("the identity's lifetime to elapse", Caller.start(expiring, CHALLENGE).ended());

    Await.until(
        "the elapsed identity to stop watching the revocation stage",
        () -> revoked.getNumberOfDependents() == 0);
  }

  @Test
  void freshAuthenticationsSharingOneRevocationFutureLeaveNothingWatchingIt() {
    var revoked = new CompletableFuture<Void>();

    for (var i = 0; i < 1_000; i++) {
      Caller.start(new Authentication(ALICE, Duration.ofDays(1), revoked), CHALLENGE).release();
    }

    assertEquals(0, revoked.getNumberOfDependents());
  }

  @Test
  void aMinimalStageConvertedOnceByTheVerifierKeepsOneCallbackHoweverManyRequestsUseIt() {
    var source = new CompletableFuture<Void>();
    var revoked = source.minimalCompletionStage().toCompletableFuture();
    var callers = new ArrayList<Caller>();

    for (var i = 0; i < 1_000; i++) {
      var caller = Caller.start(new Authentication(ALICE, Duration.ofDays(1), revoked), CHALLENGE);
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
