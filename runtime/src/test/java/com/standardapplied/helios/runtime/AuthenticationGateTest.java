/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** How long an admitted caller's identity lasts: until its lifetime, revocation or release. */
final class AuthenticationGateTest {

  private static final Principal ALICE = new Principal("acme", "alice");

  @Test
  void aGateRefusesANullAuthenticator() {
    var ex = assertThrows(NullPointerException.class, () -> new AuthenticationGate(null));
    assertEquals("authenticator must not be null", ex.getMessage());
  }

  @Test
  void aLiveIdentityStaysLiveUntilTheRequestReleasesIt() {
    var caller = Caller.start(Authentication.of(ALICE));
    assertEquals(ALICE, caller.principal());
    assertFalse(caller.ended().isDone());

    caller.release();

    assertTrue(caller.ended().isDone());
  }

  @Test
  void theIdentityEndsWhenItsLifetimeElapses() {
    var caller =
        Caller.start(new Authentication(ALICE, Duration.ofMillis(1), new CompletableFuture<>()));

    assertNull(Await.value("the identity's lifetime to elapse", caller.ended()));
  }

  @Test
  void anIdentityWithNoLifetimeLeftHasEndedAtStart() {
    var caller =
        Caller.start(new Authentication(ALICE, Duration.ofSeconds(-1), new CompletableFuture<>()));

    assertTrue(caller.ended().isDone());
  }

  @Test
  void theIdentityEndsWhenItsCredentialIsRevoked() {
    var revoked = new CompletableFuture<Void>();
    var caller = Caller.start(new Authentication(ALICE, Duration.ofDays(1), revoked));

    revoked.complete(null);

    assertTrue(caller.ended().isDone());
    assertFalse(caller.ended().isCompletedExceptionally());
  }

  @Test
  void aRevocationThatFailsAlsoEndsTheIdentity() {
    var revoked = new CompletableFuture<Void>();
    var caller = Caller.start(new Authentication(ALICE, Duration.ofDays(1), revoked));

    revoked.completeExceptionally(new IllegalStateException("revocation feed lost"));

    assertTrue(caller.ended().isDone());
    assertFalse(caller.ended().isCompletedExceptionally());
  }

  @Test
  void releasingTheCallerLeavesTheVerifiersStageUntouched() {
    var revoked = new CompletableFuture<Void>();
    var caller = Caller.start(new Authentication(ALICE, Duration.ofDays(1), revoked));

    caller.release();

    assertFalse(revoked.isDone());
  }

  @Test
  void requestsThatEndedLeaveNothingWatchingASharedRevocationStage() {
    var revoked = new CompletableFuture<Void>();
    var authentication = new Authentication(ALICE, Duration.ofDays(1), revoked);
    var expiring = new Authentication(ALICE, Duration.ofMillis(1), revoked);

    for (var i = 0; i < 1_000; i++) {
      Caller.start(authentication).release();
    }
    Await.value("the identity's lifetime to elapse", Caller.start(expiring).ended());

    assertEquals(0, revoked.getNumberOfDependents());
  }

  @Test
  void aStageThatIsNotACompletableFutureIsConvertedOnceHoweverManyRequestsShareIt() {
    var conversions = new AtomicInteger();
    var revoked =
        new CompletableFuture<Void>() {
          @Override
          public CompletableFuture<Void> toCompletableFuture() {
            conversions.incrementAndGet();
            return thenApply(ignored -> null);
          }
        };
    var authentication = new Authentication(ALICE, Duration.ofDays(1), revoked);
    var callers = new ArrayList<Caller>();

    for (var i = 0; i < 100; i++) {
      callers.add(Caller.start(authentication));
    }
    assertEquals(1, revoked.getNumberOfDependents());
    revoked.complete(null);

    assertEquals(1, conversions.get());
    assertTrue(callers.stream().allMatch(caller -> caller.ended().isDone()));
  }

  @Test
  void anAuthenticationOfAPrincipalNeitherExpiresNorIsRevoked() {
    var authentication = Authentication.of(new Principal("acme", "alice"));
    assertEquals(ChronoUnit.FOREVER.getDuration(), authentication.lifetime());
    assertFalse(authentication.revoked().toCompletableFuture().isDone());
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
