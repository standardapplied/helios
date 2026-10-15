/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * {@link SessionRegistry#sweep()} on a hand-advanced clock: it reclaims terminal sessions past
 * their retention and sessions left unstarted past the idle timeout, and never a started session
 * that has not ended.
 */
final class SessionRegistrySweepTest {

  private static final Principal ALICE = new Principal("acme", "alice");
  private static final Principal EVE = new Principal("globex", "eve");
  private static final Duration RETENTION = Duration.ofMinutes(10);
  private static final Duration IDLE = Duration.ofMinutes(2);

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-10-15T12:00:00Z"));
  private final AtomicInteger ids = new AtomicInteger();
  private final SessionRegistry registry =
      SessionRegistry.newBuilder()
          .withFactory(opts -> new StubSession(opts.sessionId()))
          .withClock(now::get)
          .withTerminalRetention(RETENTION)
          .withIdleTimeout(IDLE)
          .withMaxSessions(3)
          .build();

  @Test
  void aSweepOfAnEmptyRegistrySweepsNothing() {
    assertEquals(0, registry.sweep());
  }

  @Test
  void aSweepClosesTerminalSessionsHeldPastTheRetentionAndKeepsYoungerOnes() {
    var older = startedSession();
    older.terminate();
    advance(Duration.ofMinutes(4));
    var younger = startedSession();
    younger.terminate();
    advance(RETENTION.minusMinutes(4));

    assertEquals(1, registry.sweep());

    assertEquals(1, older.closes.get());
    assertTrue(registry.find(older.sessionId(), ALICE).isEmpty());
    assertTrue(registry.find(younger.sessionId(), ALICE).isPresent());
  }

  @Test
  void aSweepClosesSessionsLeftUnstartedPastTheIdleTimeout() {
    var unstarted = create(ALICE);
    advance(Duration.ofMinutes(1));
    var younger = create(ALICE);
    advance(IDLE.minusMinutes(1));

    assertEquals(1, registry.sweep());

    assertEquals(1, unstarted.closes.get());
    assertEquals(0, younger.closes.get());
    assertEquals(1, registry.size());
  }

  @Test
  void aStartedSessionThatHasNotEndedIsNeverSwept() {
    var working = startedSession();
    advance(Duration.ofDays(365));

    assertEquals(0, registry.sweep());

    assertEquals(0, working.closes.get());
    assertTrue(registry.find(working.sessionId(), ALICE).isPresent());
  }

  @Test
  void aSweptSessionsPlaceIsFreeAgain() {
    for (var i = 0; i < 3; i++) {
      startedSession().terminate();
    }
    assertThrows(SessionCapacityException.class, () -> create(EVE));
    advance(RETENTION);

    assertEquals(3, registry.sweep());

    create(EVE);
    create(EVE);
    create(EVE);
    assertThrows(SessionCapacityException.class, () -> create(EVE));
  }

  @Test
  void aSweepCountsEverySessionItUnregisteredWhileTheirOwnersCloseThem() {
    var a = startedSession();
    var b = startedSession();
    a.onClose = () -> assertFalse(registry.close(b.sessionId(), ALICE));
    b.onClose = () -> assertFalse(registry.close(a.sessionId(), ALICE));
    a.terminate();
    b.terminate();
    advance(RETENTION);

    assertEquals(2, registry.sweep());

    assertEquals(0, registry.size());
    assertEquals(1, a.closes.get());
    assertEquals(1, b.closes.get());
  }

  private StubSession startedSession() {
    var session = create(ALICE);
    session.send(UserMessage.text("go"));
    return session;
  }

  private StubSession create(Principal owner) {
    return (StubSession)
        registry.create(
            SessionOptions.newBuilder()
                .withModel(ScriptedModel.newBuilder().withTextTurn("x").build())
                .withSessionId("s" + ids.incrementAndGet())
                .build(),
            owner);
  }

  private void advance(Duration by) {
    now.set(now.get().plus(by));
  }
}
