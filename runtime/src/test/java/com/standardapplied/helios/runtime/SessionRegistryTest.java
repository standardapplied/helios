/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.BlockingModel;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.SessionOptions;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class SessionRegistryTest {

  private static final Principal OWNER = new Principal("acme", "alice");
  private static final Principal SAME_TENANT = new Principal("acme", "bob");
  private static final Principal OTHER_TENANT = new Principal("globex", "alice");

  private static Model stubModel() {
    return new BlockingModel() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder().withContent("x").build();
      }

      @Override
      public String id() {
        return "stub";
      }

      @Override
      public String provider() {
        return "stub";
      }
    };
  }

  private static SessionOptions optionsFor(String sessionId) {
    return SessionOptions.newBuilder().withModel(stubModel()).withSessionId(sessionId).build();
  }

  @Test
  void inMemoryFactoryProducesEmptyRegistry() {
    var registry = SessionRegistry.inMemory();
    assertEquals(0, registry.size());
    assertTrue(registry.sessionIds().isEmpty());
  }

  @Test
  void withFactoryRejectsNullFactory() {
    var ex = assertThrows(NullPointerException.class, () -> SessionRegistry.withFactory(null));
    assertEquals("factory must not be null", ex.getMessage());
  }

  @Test
  void createRegistersAndReturnsSession() {
    var registry = SessionRegistry.inMemory();
    var session = registry.create(optionsFor("s1"), OWNER);
    assertEquals("s1", session.sessionId());
    assertEquals(1, registry.size());
    assertTrue(registry.find("s1", OWNER).isPresent());
    registry.closeAll();
  }

  @Test
  void createRejectsNullOptions() {
    var registry = SessionRegistry.inMemory();
    var ex = assertThrows(NullPointerException.class, () -> registry.create(null, OWNER));
    assertEquals("options must not be null", ex.getMessage());
  }

  @Test
  void createRejectsDuplicateSessionId() {
    var registry = SessionRegistry.inMemory();
    registry.create(optionsFor("dup"), OWNER);
    var ex =
        assertThrows(IllegalStateException.class, () -> registry.create(optionsFor("dup"), OWNER));
    assertTrue(ex.getMessage().contains("already registered"));
    assertEquals(1, registry.size());
    registry.closeAll();
  }

  @Test
  void createRejectsFactoryReturningNull() {
    var registry = SessionRegistry.withFactory(opts -> null);
    var ex =
        assertThrows(
            NullPointerException.class, () -> registry.create(optionsFor("ignored"), OWNER));
    assertEquals("factory returned null session", ex.getMessage());
  }

  @Test
  void getRejectsNullId() {
    var registry = SessionRegistry.inMemory();
    var ex = assertThrows(NullPointerException.class, () -> registry.find(null, OWNER));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  @Test
  void getReturnsEmptyForUnknownId() {
    assertTrue(SessionRegistry.inMemory().find("nope", OWNER).isEmpty());
  }

  @Test
  void getReturnsSameInstanceAcrossCalls() {
    var registry = SessionRegistry.inMemory();
    var session = registry.create(optionsFor("s1"), OWNER);
    assertSame(session, registry.find("s1", OWNER).orElseThrow());
    assertSame(session, registry.find("s1", OWNER).orElseThrow());
    registry.closeAll();
  }

  @Test
  void closeRemovesAndClosesSession() {
    var registry = SessionRegistry.inMemory();
    registry.create(optionsFor("s1"), OWNER);
    assertTrue(registry.close("s1", OWNER));
    assertEquals(0, registry.size());
    assertTrue(registry.find("s1", OWNER).isEmpty());
  }

  @Test
  void closeReturnsFalseForUnknownId() {
    assertFalse(SessionRegistry.inMemory().close("nope", OWNER));
  }

  @Test
  void closeRejectsNullId() {
    var registry = SessionRegistry.inMemory();
    var ex = assertThrows(NullPointerException.class, () -> registry.close(null, OWNER));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  // ── ownership ────────────────────────────────────────────────────────────

  @Test
  void createRejectsNullOwner() {
    var registry = SessionRegistry.inMemory();
    var ex =
        assertThrows(NullPointerException.class, () -> registry.create(optionsFor("s1"), null));
    assertEquals("owner must not be null", ex.getMessage());
    assertEquals(0, registry.size());
  }

  @Test
  void findRejectsNullRequester() {
    var registry = SessionRegistry.inMemory();
    var ex = assertThrows(NullPointerException.class, () -> registry.find("s1", null));
    assertEquals("requester must not be null", ex.getMessage());
  }

  @Test
  void closeRejectsNullRequester() {
    var registry = SessionRegistry.inMemory();
    var ex = assertThrows(NullPointerException.class, () -> registry.close("s1", null));
    assertEquals("requester must not be null", ex.getMessage());
  }

  @Test
  void anotherSubjectOfTheSameTenantCannotFindTheSession() {
    var registry = SessionRegistry.inMemory();
    registry.create(optionsFor("s1"), OWNER);
    assertTrue(registry.find("s1", SAME_TENANT).isEmpty());
    registry.closeAll();
  }

  @Test
  void theSameSubjectInAnotherTenantCannotFindTheSession() {
    var registry = SessionRegistry.inMemory();
    registry.create(optionsFor("s1"), OWNER);
    assertTrue(registry.find("s1", OTHER_TENANT).isEmpty());
    registry.closeAll();
  }

  @Test
  void anotherPrincipalCannotCloseTheSession() {
    var closeCount = new AtomicInteger();
    var registry =
        SessionRegistry.withFactory(opts -> new TrackingSession(opts.sessionId(), closeCount));
    registry.create(optionsFor("s1"), OWNER);

    assertFalse(registry.close("s1", SAME_TENANT));
    assertFalse(registry.close("s1", OTHER_TENANT));

    assertEquals(0, closeCount.get());
    assertTrue(registry.find("s1", OWNER).isPresent());
    registry.closeAll();
  }

  @Test
  void aDuplicateIdFromAnotherPrincipalLeavesTheOwnerInPlace() {
    var registry = SessionRegistry.inMemory();
    var original = registry.create(optionsFor("s1"), OWNER);

    assertThrows(
        IllegalStateException.class, () -> registry.create(optionsFor("s1"), OTHER_TENANT));

    assertSame(original, registry.find("s1", OWNER).orElseThrow());
    assertTrue(registry.find("s1", OTHER_TENANT).isEmpty());
    registry.closeAll();
  }

  @Test
  void closeAllUnregistersAllSessions() {
    var registry = SessionRegistry.inMemory();
    registry.create(optionsFor("s1"), OWNER);
    registry.create(optionsFor("s2"), OWNER);
    registry.create(optionsFor("s3"), OWNER);
    assertEquals(3, registry.size());
    registry.closeAll();
    assertEquals(0, registry.size());
  }

  @Test
  void closeAllOnEmptyRegistryIsNoOp() {
    var registry = SessionRegistry.inMemory();
    registry.closeAll();
    assertEquals(0, registry.size());
  }

  @Test
  void sessionIdsSnapshotIsImmutable() {
    var registry = SessionRegistry.inMemory();
    registry.create(optionsFor("s1"), OWNER);
    var snapshot = registry.sessionIds();
    assertEquals(1, snapshot.size());
    registry.create(optionsFor("s2"), OWNER);
    assertEquals(1, snapshot.size(), "snapshot is point-in-time");
    assertEquals(2, registry.size());
    registry.closeAll();
  }

  @Test
  void duplicateIdClosesTheRejectedSession() {
    var closeCount = new AtomicInteger();
    var registry =
        SessionRegistry.withFactory(opts -> new TrackingSession(opts.sessionId(), closeCount));
    registry.create(optionsFor("s1"), OWNER);
    assertThrows(IllegalStateException.class, () -> registry.create(optionsFor("s1"), OWNER));
    // The freshly-built tracking session for the rejected duplicate should be closed once;
    // the original remains open until we tear down.
    assertEquals(1, closeCount.get(), "rejected duplicate must close its freshly-built session");
    registry.closeAll();
    assertEquals(2, closeCount.get(), "original closes during closeAll");
  }

  @Test
  void createReturnsFreshInstancePerCall() {
    var registry = SessionRegistry.inMemory();
    var a = registry.create(optionsFor("a"), OWNER);
    var b = registry.create(optionsFor("b"), OWNER);
    assertNotSame(a, b);
    registry.closeAll();
  }

  // ── Builder ──────────────────────────────────────────────────────────────

  @Test
  void newBuilderProducesDefaults() {
    var registry = SessionRegistry.newBuilder().build();
    assertEquals(0, registry.size());
  }

  @Test
  void builderWithAllOptionsAppliesEverySetter() {
    // Exercise every Builder setter in one go so a missed default propagation surfaces here.
    var closeCount = new AtomicInteger();
    java.util.function.Function<com.standardapplied.helios.session.SessionOptions, AgentSession>
        customFactory = opts -> new TrackingSession(opts.sessionId(), closeCount);
    var clock =
        java.time.Clock.fixed(
            java.time.Instant.parse("2026-05-16T20:00:00Z"), java.time.ZoneOffset.UTC);
    var registry =
        SessionRegistry.newBuilder()
            .withFactory(customFactory)
            .withClock(clock)
            .withMaxSessions(7)
            .build();
    // Factory: passes through to create().
    var session = (TrackingSession) registry.create(optionsFor("s1"), OWNER);
    assertEquals("s1", session.sessionId());
    // Clock: drives terminatedAt timestamping; close session and confirm purge with age 0 evicts.
    session.future.complete(stubTerminal("s1"));
    assertEquals(1, registry.purgeTerminalOlderThan(java.time.Duration.ZERO));
    // MaxSessions: now empty; create up to cap.
    for (int i = 0; i < 7; i++) {
      registry.create(optionsFor("c" + i), OWNER);
    }
    assertEquals(7, registry.size());
    // Eighth create with no terminal to evict → reject.
    assertThrows(IllegalStateException.class, () -> registry.create(optionsFor("overflow"), OWNER));
    registry.closeAll();
  }

  @Test
  void builderRejectsNullFactory() {
    var b = SessionRegistry.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withFactory(null));
    assertEquals("factory must not be null", ex.getMessage());
  }

  @Test
  void builderRejectsNullClock() {
    var b = SessionRegistry.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withClock(null));
    assertEquals("clock must not be null", ex.getMessage());
  }

  @Test
  void builderRejectsZeroMaxSessions() {
    var b = SessionRegistry.newBuilder();
    var ex = assertThrows(IllegalArgumentException.class, () -> b.withMaxSessions(0));
    assertEquals("maxSessions must be positive, got 0", ex.getMessage());
  }

  @Test
  void builderRejectsNegativeMaxSessions() {
    var b = SessionRegistry.newBuilder();
    var ex = assertThrows(IllegalArgumentException.class, () -> b.withMaxSessions(-5));
    assertEquals("maxSessions must be positive, got -5", ex.getMessage());
  }

  // ── purgeTerminalOlderThan ───────────────────────────────────────────────

  @Test
  void purgeRejectsNullAge() {
    var registry = SessionRegistry.inMemory();
    var ex = assertThrows(NullPointerException.class, () -> registry.purgeTerminalOlderThan(null));
    assertEquals("age must not be null", ex.getMessage());
  }

  @Test
  void purgeRejectsNegativeAge() {
    var registry = SessionRegistry.inMemory();
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.purgeTerminalOlderThan(java.time.Duration.ofSeconds(-1)));
    assertTrue(ex.getMessage().startsWith("age must be non-negative"));
  }

  @Test
  void purgeEmptyRegistryReturnsZero() {
    var registry = SessionRegistry.inMemory();
    assertEquals(0, registry.purgeTerminalOlderThan(java.time.Duration.ofSeconds(1)));
  }

  @Test
  void purgeLeavesLiveSessionsAlone() {
    var closeCount = new AtomicInteger();
    var clock =
        java.time.Clock.fixed(
            java.time.Instant.parse("2026-05-16T12:00:00Z"), java.time.ZoneOffset.UTC);
    var registry =
        SessionRegistry.newBuilder()
            .withFactory(opts -> new TrackingSession(opts.sessionId(), closeCount))
            .withClock(clock)
            .build();
    registry.create(optionsFor("live"), OWNER);
    // Live → terminatedAt remains null → not purged regardless of age.
    assertEquals(0, registry.purgeTerminalOlderThan(java.time.Duration.ZERO));
    assertEquals(1, registry.size());
    registry.closeAll();
  }

  @Test
  void purgeRemovesTerminalSessionsOlderThanCutoff() {
    var closeCount = new AtomicInteger();
    var clock = new MutableClock(java.time.Instant.parse("2026-05-16T12:00:00Z"));
    var registry =
        SessionRegistry.newBuilder()
            .withFactory(opts -> new TrackingSession(opts.sessionId(), closeCount))
            .withClock(clock)
            .build();
    var old = (TrackingSession) registry.create(optionsFor("old"), OWNER);
    old.future.complete(stubTerminal(old.sessionId()));
    // Advance clock 10 s.
    clock.advance(java.time.Duration.ofSeconds(10));
    var fresh = (TrackingSession) registry.create(optionsFor("fresh"), OWNER);
    fresh.future.complete(stubTerminal(fresh.sessionId()));
    // Now: old was terminated at T+0, fresh at T+10, now is T+10. Purge anything ≥5 s old.
    var purged = registry.purgeTerminalOlderThan(java.time.Duration.ofSeconds(5));
    assertEquals(1, purged);
    assertTrue(registry.find("fresh", OWNER).isPresent());
    assertFalse(registry.find("old", OWNER).isPresent());
    registry.closeAll();
  }

  @Test
  void purgeAgeZeroEvictsEveryTerminal() {
    var closeCount = new AtomicInteger();
    var clock = new MutableClock(java.time.Instant.parse("2026-05-16T12:00:00Z"));
    var registry =
        SessionRegistry.newBuilder()
            .withFactory(opts -> new TrackingSession(opts.sessionId(), closeCount))
            .withClock(clock)
            .build();
    var a = (TrackingSession) registry.create(optionsFor("a"), OWNER);
    var b = (TrackingSession) registry.create(optionsFor("b"), OWNER);
    a.future.complete(stubTerminal(a.sessionId()));
    b.future.complete(stubTerminal(b.sessionId()));
    assertEquals(2, registry.purgeTerminalOlderThan(java.time.Duration.ZERO));
    assertEquals(0, registry.size());
  }

  // ── withMaxSessions cap ──────────────────────────────────────────────────

  @Test
  void capRejectsWhenAllSessionsAreLive() {
    var closeCount = new AtomicInteger();
    var registry =
        SessionRegistry.newBuilder()
            .withFactory(opts -> new TrackingSession(opts.sessionId(), closeCount))
            .withMaxSessions(2)
            .build();
    registry.create(optionsFor("s1"), OWNER);
    registry.create(optionsFor("s2"), OWNER);
    var ex =
        assertThrows(IllegalStateException.class, () -> registry.create(optionsFor("s3"), OWNER));
    assertTrue(ex.getMessage().contains("registry at capacity"));
    assertEquals(2, registry.size());
    registry.closeAll();
  }

  @Test
  void capEvictsOldestTerminalWhenAtLimit() {
    var closeCount = new AtomicInteger();
    var clock = new MutableClock(java.time.Instant.parse("2026-05-16T12:00:00Z"));
    var registry =
        SessionRegistry.newBuilder()
            .withFactory(opts -> new TrackingSession(opts.sessionId(), closeCount))
            .withClock(clock)
            .withMaxSessions(2)
            .build();
    var a = (TrackingSession) registry.create(optionsFor("a"), OWNER);
    a.future.complete(stubTerminal(a.sessionId()));
    clock.advance(java.time.Duration.ofSeconds(1));
    var b = (TrackingSession) registry.create(optionsFor("b"), OWNER);
    b.future.complete(stubTerminal(b.sessionId()));
    clock.advance(java.time.Duration.ofSeconds(1));
    // Now at cap with two terminals. Creating "c" should evict "a" (oldest terminal).
    registry.create(optionsFor("c"), OWNER);
    assertEquals(2, registry.size());
    assertFalse(registry.find("a", OWNER).isPresent());
    assertTrue(registry.find("b", OWNER).isPresent());
    assertTrue(registry.find("c", OWNER).isPresent());
    registry.closeAll();
  }

  @Test
  void capDoesNotEvictWhenUnderLimit() {
    var closeCount = new AtomicInteger();
    var registry =
        SessionRegistry.newBuilder()
            .withFactory(opts -> new TrackingSession(opts.sessionId(), closeCount))
            .withMaxSessions(10)
            .build();
    var a = (TrackingSession) registry.create(optionsFor("a"), OWNER);
    a.future.complete(stubTerminal(a.sessionId()));
    registry.create(optionsFor("b"), OWNER);
    assertEquals(2, registry.size());
    assertTrue(registry.find("a", OWNER).isPresent());
    registry.closeAll();
  }

  /** Synthesize a terminal ResultMessage so the registry timestamps termination. */
  private static com.standardapplied.helios.session.ResultMessage stubTerminal(String sessionId) {
    return new com.standardapplied.helios.session.ResultMessage.Success(
        sessionId,
        "ok",
        com.standardapplied.helios.core.model.Response.Usage.of(0, 0),
        com.standardapplied.helios.core.common.CostEstimate.zero(),
        java.time.Duration.ZERO,
        java.util.List.of());
  }

  /** Test clock that lets us advance time by hand. */
  private static final class MutableClock extends java.time.Clock {
    private final java.util.concurrent.atomic.AtomicReference<java.time.Instant> now;

    MutableClock(java.time.Instant initial) {
      this.now = new java.util.concurrent.atomic.AtomicReference<>(initial);
    }

    void advance(java.time.Duration by) {
      now.updateAndGet(i -> i.plus(by));
    }

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public java.time.Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public java.time.Instant instant() {
      return now.get();
    }
  }

  /** Minimal AgentSession used by the duplicate-id test to assert close() is called. */
  private static final class TrackingSession implements AgentSession {

    private final String sessionId;
    private final AtomicInteger closeCount;
    private final java.util.concurrent.CompletableFuture<
            com.standardapplied.helios.session.ResultMessage>
        future = new java.util.concurrent.CompletableFuture<>();

    TrackingSession(String sessionId, AtomicInteger closeCount) {
      this.sessionId = sessionId;
      this.closeCount = closeCount;
    }

    @Override
    public void send(com.standardapplied.helios.session.UserMessage message) {}

    @Override
    public void interrupt(String reason) {}

    @Override
    public java.util.concurrent.Flow.Publisher<com.standardapplied.helios.session.QueryEvent>
        events() {
      return s -> {};
    }

    @Override
    public java.util.concurrent.CompletableFuture<com.standardapplied.helios.session.ResultMessage>
        result() {
      return future;
    }

    @Override
    public String sessionId() {
      return sessionId;
    }

    @Override
    public long currentTurnIndex() {
      return 0;
    }

    @Override
    public void close() {
      closeCount.incrementAndGet();
    }

    @Override
    public void answer(
        String questionId,
        com.standardapplied.helios.session.ask.AskUserQuestionResponse response) {}
  }
}
