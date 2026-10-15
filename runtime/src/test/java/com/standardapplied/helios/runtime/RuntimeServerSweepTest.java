/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;

/**
 * {@link RuntimeServer} sweeps its registry on a schedule of its own, and survives a failed sweep.
 */
final class RuntimeServerSweepTest {

  private static final Principal ALICE = new Principal("acme", "alice");
  private static final Duration RETENTION = Duration.ofMinutes(1);

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-10-15T12:00:00Z"));
  private final AtomicBoolean clockFails = new AtomicBoolean();
  private final SessionRegistry registry =
      SessionRegistry.newBuilder()
          .withFactory(opts -> new StubSession(opts.sessionId()))
          .withClock(this::instant)
          .withTerminalRetention(RETENTION)
          .build();

  @Test
  void withRequestLimitsRejectsNull() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> RuntimeServer.builder().withRequestLimits(null));
    assertEquals("requestLimits must not be null", ex.getMessage());
  }

  @Test
  void withSweepIntervalRejectsNull() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> RuntimeServer.builder().withSweepInterval(null));
    assertEquals("sweepInterval must not be null", ex.getMessage());
  }

  @Test
  void withSweepIntervalRejectsLessThanAMillisecond() {
    var builder = RuntimeServer.builder();
    var tooShort = Duration.ofNanos(999_999);

    var ex =
        assertThrows(IllegalArgumentException.class, () -> builder.withSweepInterval(tooShort));

    assertEquals("sweepInterval must be at least 1 ms, got PT0.000999999S", ex.getMessage());
  }

  @Test
  void theServerSweepsATerminalSessionPastItsRetention() {
    var closed = new CountDownLatch(1);
    var session = terminalSession(closed);

    try (var server = server()) {
      now.set(now.get().plus(RETENTION));

      Await.latch("the scheduled sweep to close the session", closed);
      assertEquals(0, server.registry().size());
    }
    assertEquals(1, session.closes.get());
  }

  @Test
  void aSweepThatFailsIsLoggedAndTheNextSweepStillRuns() {
    var closed = new CountDownLatch(1);
    terminalSession(closed);

    try (var log = LogCapture.of(RuntimeServer.class);
        var server = server()) {
      clockFails.set(true);
      now.set(now.get().plus(RETENTION));

      Await.latch("a later sweep to close the session", closed);
      var failure = log.records().getFirst();
      assertEquals(Level.WARNING, failure.getLevel());
      assertEquals("session sweep failed", failure.getMessage());
      assertEquals("clock unavailable", failure.getThrown().getMessage());
    }
  }

  @Test
  void aServerKeepsSweepingItsOwnRegistryWhenItsBuilderIsReused() {
    var closed = new CountDownLatch(1);
    terminalSession(closed);
    var builder = builder();

    try (var server = builder.build()) {
      builder.withRegistry(SessionRegistry.inMemory());
      now.set(now.get().plus(RETENTION));

      Await.latch("the server's own registry to be swept", closed);
      assertEquals(0, server.registry().size());
    }
  }

  @Test
  void closingTheServerLetsASweepUnderWayFinishClosingItsSession() {
    var closing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closed = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    var session = terminalSession(closed);
    session.onClose =
        () -> {
          closing.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            interrupted.set(true);
          }
          closed.countDown();
        };
    var server = server();
    now.set(now.get().plus(RETENTION));
    Await.latch("the sweep to start closing the session", closing);

    server.close();
    release.countDown();

    Await.latch("the sweep to finish closing the session", closed);
    assertFalse(interrupted.get());
  }

  private Instant instant() {
    if (clockFails.getAndSet(false)) {
      throw new IllegalStateException("clock unavailable");
    }
    return now.get();
  }

  private StubSession terminalSession(CountDownLatch closed) {
    var session =
        (StubSession)
            registry.create(
                SessionOptions.newBuilder()
                    .withModel(ScriptedModel.newBuilder().withTextTurn("x").build())
                    .withSessionId("s1")
                    .build(),
                ALICE);
    session.onClose = closed::countDown;
    session.terminate();
    return session;
  }

  private RuntimeServer server() {
    return builder().build();
  }

  private RuntimeServer.Builder builder() {
    return RuntimeServer.builder()
        .withRegistry(registry)
        .withOptionsFactory(
            id -> {
              throw new IllegalStateException("no session is created over HTTP here");
            })
        .withoutAuthentication()
        .withSweepInterval(Duration.ofMillis(1));
  }
}
