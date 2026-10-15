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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
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
    assertEquals(0, registry.size());
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
    var closes = new AtomicInteger();
    var registry = SessionRegistry.withFactory(opts -> stub(opts, closes));
    registry.create(optionsFor("s1"), OWNER);

    assertFalse(registry.close("s1", SAME_TENANT));
    assertFalse(registry.close("s1", OTHER_TENANT));

    assertEquals(0, closes.get());
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
    registry.create(optionsFor("s2"), SAME_TENANT);
    registry.create(optionsFor("s3"), OTHER_TENANT);
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
  void duplicateIdClosesTheRejectedSession() {
    var closes = new AtomicInteger();
    var registry = SessionRegistry.withFactory(opts -> stub(opts, closes));
    registry.create(optionsFor("s1"), OWNER);
    assertThrows(IllegalStateException.class, () -> registry.create(optionsFor("s1"), OWNER));
    // The freshly-built tracking session for the rejected duplicate should be closed once;
    // the original remains open until we tear down.
    assertEquals(1, closes.get(), "rejected duplicate must close its freshly-built session");
    registry.closeAll();
    assertEquals(2, closes.get(), "original closes during closeAll");
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
  void builderRejectsEveryCapThatIsNotPositive() {
    var b = SessionRegistry.newBuilder();
    Map<String, IntConsumer> caps =
        Map.of(
            "maxSessions", b::withMaxSessions,
            "maxActiveSessions", b::withMaxActiveSessions,
            "maxSessionsPerPrincipal", b::withMaxSessionsPerPrincipal,
            "maxActiveSessionsPerPrincipal", b::withMaxActiveSessionsPerPrincipal);
    caps.forEach(
        (name, setter) -> {
          for (var value : new int[] {0, -5}) {
            var ex = assertThrows(IllegalArgumentException.class, () -> setter.accept(value));
            assertEquals(name + " must be positive, got " + value, ex.getMessage());
          }
        });
  }

  @Test
  void builderRejectsEveryDurationThatIsNullOrNotPositive() {
    var b = SessionRegistry.newBuilder();
    Map<String, Consumer<Duration>> durations =
        Map.of("terminalRetention", b::withTerminalRetention, "idleTimeout", b::withIdleTimeout);
    durations.forEach(
        (name, setter) -> {
          var missing = assertThrows(NullPointerException.class, () -> setter.accept(null));
          assertEquals(name + " must not be null", missing.getMessage());
          for (var value : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            var ex = assertThrows(IllegalArgumentException.class, () -> setter.accept(value));
            assertEquals(name + " must be positive, got " + value, ex.getMessage());
          }
        });
  }

  // ── Defaults ─────────────────────────────────────────────────────────────

  @Test
  void byDefaultAPrincipalHoldsSixteenActiveSessions() {
    var registry = SessionRegistry.withFactory(opts -> new StubSession(opts.sessionId()));
    createAll(registry, OWNER, 16);

    var ex = assertThrows(SessionCapacityException.class, () -> create(registry, OWNER));

    assertEquals(SessionCapacityException.Scope.PRINCIPAL, ex.scope());
    assertEquals("active sessions of the principal at cap 16", ex.getMessage());
  }

  @Test
  void byDefaultTheRegistryHoldsTwoHundredFiftySixActiveSessions() {
    var registry = SessionRegistry.withFactory(opts -> new StubSession(opts.sessionId()));
    for (var i = 0; i < 16; i++) {
      createAll(registry, new Principal("tenant-" + i, "user"), 16);
    }

    var ex = assertThrows(SessionCapacityException.class, () -> create(registry, OWNER));

    assertEquals(SessionCapacityException.Scope.SERVER, ex.scope());
    assertEquals("active sessions at cap 256", ex.getMessage());
  }

  @Test
  void byDefaultAPrincipalHoldsSixtyFourSessionsBeforeItsOldestTerminalIsEvicted() {
    var registry = SessionRegistry.withFactory(opts -> new StubSession(opts.sessionId()));
    var first = (StubSession) create(registry, OWNER);
    first.terminate();
    for (var i = 1; i < 64; i++) {
      ((StubSession) create(registry, OWNER)).terminate();
    }

    create(registry, OWNER);

    assertEquals(64, registry.size());
    assertEquals(1, first.closes.get());
  }

  @Test
  void byDefaultTheRegistryHoldsOneThousandTwentyFourSessions() {
    var registry = SessionRegistry.withFactory(opts -> new StubSession(opts.sessionId()));
    for (var i = 0; i < 16; i++) {
      var tenant = new Principal("tenant-" + i, "user");
      for (var j = 0; j < 64; j++) {
        ((StubSession) create(registry, tenant)).terminate();
      }
    }

    var ex = assertThrows(SessionCapacityException.class, () -> create(registry, OWNER));

    assertEquals(SessionCapacityException.Scope.SERVER, ex.scope());
    assertEquals("sessions at cap 1024", ex.getMessage());
    assertEquals(1024, registry.size());
  }

  @Test
  void byDefaultATerminalSessionIsRetainedFifteenMinutesAndAnUnstartedOneFive() {
    var now = new AtomicReference<>(Instant.parse("2026-10-15T12:00:00Z"));
    var registry =
        SessionRegistry.newBuilder()
            .withFactory(opts -> new StubSession(opts.sessionId()))
            .withClock(now::get)
            .build();
    var terminal = (StubSession) create(registry, OWNER);
    terminal.terminate();
    create(registry, OWNER);

    now.set(now.get().plus(Duration.ofMinutes(5)).minusMillis(1));
    assertEquals(0, registry.sweep());
    now.set(now.get().plusMillis(1));
    assertEquals(1, registry.sweep());
    now.set(now.get().plus(Duration.ofMinutes(10)).minusMillis(1));
    assertEquals(0, registry.sweep());
    now.set(now.get().plusMillis(1));
    assertEquals(1, registry.sweep());
    assertEquals(0, registry.size());
  }

  private static final AtomicInteger IDS = new AtomicInteger();

  private static AgentSession create(SessionRegistry registry, Principal owner) {
    return registry.create(optionsFor("s" + IDS.incrementAndGet()), owner);
  }

  private static void createAll(SessionRegistry registry, Principal owner, int count) {
    for (var i = 0; i < count; i++) {
      create(registry, owner);
    }
  }

  private static StubSession stub(SessionOptions options, AtomicInteger closes) {
    var session = new StubSession(options.sessionId());
    session.onClose = closes::incrementAndGet;
    return session;
  }
}
