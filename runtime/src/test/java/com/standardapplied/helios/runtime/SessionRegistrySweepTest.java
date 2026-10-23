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
 * {@link SessionRegistry#sweep()} on a hand-advanced clock: it reclaims a session not running once
 * it has not been running for the retention, a terminal session from its end and one never started
 * from its registration, and never a started session that has not ended.
 */
final class SessionRegistrySweepTest {

  private static final Principal ALICE = new Principal("acme", "alice");
  private static final Principal EVE = new Principal("globex", "eve");

  /** The default retention. */
  private static final Duration RETENTION = Duration.ofMinutes(15);

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-10-15T12:00:00Z"));
  private final AtomicInteger ids = new AtomicInteger();
  private final SessionRegistry registry =
      SessionRegistry.newBuilder()
          .withFactory(opts -> new StubSession(opts.sessionId()))
          .withClock(now::get)
          .withMaxSessions(3)
          .build();

  @Test
  void aSweepOfAnEmptyRegistrySweepsNothing() {
    assertEquals(0, registry.sweep());
  }

  @Test
  void aSweepClosesTerminalSessionsOfEveryOwnerHeldPastTheRetentionAndKeepsYoungerOnes() {
    var older = startedSession(EVE);
    older.terminate();
    advance(Duration.ofMinutes(4));
    var younger = startedSession();
    younger.terminate();
    advance(RETENTION.minusMinutes(4));

    assertEquals(1, registry.sweep());

    assertEquals(1, older.closes.get());
    assertTrue(registry.find(older.sessionId(), EVE).isEmpty());
    assertTrue(registry.find(younger.sessionId(), ALICE).isPresent());
  }

  @Test
  void aTerminalSessionIsRetainedFromItsEndNotItsRegistration() {
    var working = startedSession();
    advance(RETENTION);
    working.terminate();
    advance(RETENTION.minusMillis(1));

    assertEquals(0, registry.sweep());
    advance(Duration.ofMillis(1));
    assertEquals(1, registry.sweep());

    assertEquals(1, working.closes.get());
  }

  @Test
  void aNeverStartedSessionIsRetainedFromItsRegistration() {
    var unstarted = create(ALICE);
    advance(RETENTION.minusMillis(1));

    assertEquals(0, registry.sweep());
    advance(Duration.ofMillis(1));
    assertEquals(1, registry.sweep());

    assertEquals(1, unstarted.closes.get());
  }

  @Test
  void aSweepClosesSessionsLeftUnstartedPastTheRetention() {
    var unstarted = create(ALICE);
    advance(Duration.ofMinutes(1));
    var younger = create(ALICE);
    advance(RETENTION.minusMinutes(1));

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

  @Test
  void aSweepClosesEverySessionItUnregisteredEvenWhenAClosingOneThrowsAnError() {
    var first = create(ALICE);
    var second = create(ALICE);
    Runnable failing =
        () -> {
          throw new AssertionError("close failed");
        };
    first.onClose = failing;
    second.onClose = failing;
    advance(RETENTION);

    try (var log = LogCapture.of(SessionRegistry.class)) {
      assertEquals(2, registry.sweep());

      assertEquals(2, log.records().size());
    }
    assertEquals(1, first.closes.get());
    assertEquals(1, second.closes.get());
    create(EVE);
    create(EVE);
    create(EVE);
  }

  @Test
  void aConfiguredRetentionAppliesToTerminalAndNeverStartedSessionsAlike() {
    var retention = Duration.ofMinutes(1);
    var configured =
        SessionRegistry.newBuilder()
            .withFactory(opts -> new StubSession(opts.sessionId()))
            .withClock(now::get)
            .withRetention(retention)
            .build();
    var terminal = (StubSession) configured.create(options(), ALICE);
    terminal.send(UserMessage.text("go"));
    terminal.terminate();
    configured.create(options(), ALICE);
    advance(retention.minusMillis(1));

    assertEquals(0, configured.sweep());
    advance(Duration.ofMillis(1));
    assertEquals(2, configured.sweep());
  }

  private StubSession startedSession() {
    return startedSession(ALICE);
  }

  private StubSession startedSession(Principal owner) {
    var session = create(owner);
    session.send(UserMessage.text("go"));
    return session;
  }

  private StubSession create(Principal owner) {
    return (StubSession) registry.create(options(), owner);
  }

  private SessionOptions options() {
    return SessionOptions.newBuilder()
        .withModel(ScriptedModel.newBuilder().withTextTurn("x").build())
        .withSessionId("s" + ids.incrementAndGet())
        .build();
  }

  private void advance(Duration by) {
    now.set(now.get().plus(by));
  }
}
