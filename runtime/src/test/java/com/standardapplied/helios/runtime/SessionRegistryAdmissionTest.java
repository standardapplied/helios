/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.runtime.SessionCapacityException.Scope;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.execution.ExecutionRequest;
import com.standardapplied.helios.session.execution.ExecutionResult;
import com.standardapplied.helios.session.execution.Runtime;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;

/**
 * Admission in {@link SessionRegistry}: the two caps hold under concurrency, a reservation is given
 * back exactly once on every path, and room is made only from the requester's own terminal
 * sessions.
 */
final class SessionRegistryAdmissionTest {

  private static final Principal ALICE = new Principal("acme", "alice");
  private static final Principal EVE = new Principal("globex", "eve");
  private static final Principal CAROL = new Principal("initech", "carol");

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-10-15T12:00:00Z"));
  private final AtomicInteger ids = new AtomicInteger();

  // ── hard caps under concurrency ──────────────────────────────────────────

  @Test
  void concurrentCreatesOfOnePrincipalNeverPassItsCapWhileFactoriesBlock() throws Exception {
    var outcome =
        createConcurrently(
            stubs().withMaxSessionsPerPrincipal(3), Collections.nCopies(7, ALICE), 3);

    assertEquals(3, outcome.created());
    assertEquals(Collections.nCopies(4, Scope.PRINCIPAL), outcome.refusals());
    assertEquals(3, outcome.factoryRuns());
    assertEquals(3, outcome.registry().size());
  }

  @Test
  void concurrentCreatesOfManyPrincipalsNeverPassTheServerCapWhileFactoriesBlock()
      throws Exception {
    var owners = new ArrayList<Principal>();
    for (var i = 0; i < 6; i++) {
      owners.add(new Principal("tenant-" + i, "user"));
    }

    var outcome = createConcurrently(stubs().withMaxSessions(2), owners, 2);

    assertEquals(2, outcome.created());
    assertEquals(Collections.nCopies(4, Scope.SERVER), outcome.refusals());
    assertEquals(2, outcome.factoryRuns());
    assertEquals(2, outcome.registry().size());
  }

  /**
   * Run one create per owner at once against a factory that blocks until released. Every create
   * beyond the first {@code admitted} is refused while the admitted ones are still inside the
   * factory; only then is the factory released.
   */
  private Outcome createConcurrently(
      SessionRegistry.Builder builder, List<Principal> owners, int admitted) throws Exception {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(admitted);
    var factoryRuns = new AtomicInteger();
    var registry =
        builder
            .withFactory(
                opts -> {
                  factoryRuns.incrementAndGet();
                  entered.countDown();
                  Await.latch("the test to release the factory", release);
                  return new StubSession(opts.sessionId());
                })
            .build();
    var refused = new CountDownLatch(owners.size() - admitted);
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var creates = new ArrayList<Future<?>>();
    for (var owner : owners) {
      var options = options();
      creates.add(
          executor.submit(
              () -> {
                try {
                  return registry.create(options, owner);
                } catch (SessionCapacityException e) {
                  refused.countDown();
                  throw e;
                }
              }));
    }
    Await.latch("every create beyond the cap to be refused", refused);
    Await.latch("the admitted creates to enter the factory", entered);
    release.countDown();
    executor.shutdown();
    Await.termination("every create to end", executor);
    var refusals = new ArrayList<Scope>();
    var created = 0;
    for (var create : creates) {
      if (create.state() == Future.State.SUCCESS) {
        created++;
      } else {
        refusals.add(
            assertInstanceOf(SessionCapacityException.class, create.exceptionNow()).scope());
      }
    }
    return new Outcome(registry, created, refusals, factoryRuns.get());
  }

  private record Outcome(
      SessionRegistry registry, int created, List<Scope> refusals, int factoryRuns) {}

  // ── caps ─────────────────────────────────────────────────────────────────

  @Test
  void aPrincipalAtItsCapIsRefusedAndARegistryAtItsCapRefusesAnother() {
    var registry = stubs().withMaxSessionsPerPrincipal(2).withMaxSessions(3).build();
    startedSession(registry, ALICE);
    startedSession(registry, ALICE);

    var principal = assertThrows(SessionCapacityException.class, () -> create(registry, ALICE));
    create(registry, EVE);
    var server = assertThrows(SessionCapacityException.class, () -> create(registry, CAROL));

    assertEquals(Scope.PRINCIPAL, principal.scope());
    assertEquals("sessions of the principal at cap 2", principal.getMessage());
    assertEquals(Scope.SERVER, server.scope());
    assertEquals("sessions at cap 3", server.getMessage());
    assertEquals(3, registry.size());
  }

  @Test
  void aPrincipalAtItsCapIsRefusedTheSameWhateverOthersHold() {
    var registry = stubs().withMaxSessionsPerPrincipal(1).withMaxSessions(2).build();
    create(registry, ALICE);

    var alone = assertThrows(SessionCapacityException.class, () -> create(registry, ALICE));
    create(registry, EVE);
    var crowded = assertThrows(SessionCapacityException.class, () -> create(registry, ALICE));

    assertEquals(Scope.PRINCIPAL, alone.scope());
    assertEquals("sessions of the principal at cap 1", alone.getMessage());
    assertEquals(alone.scope(), crowded.scope());
    assertEquals(alone.getMessage(), crowded.getMessage());
  }

  @Test
  void byDefaultAPrincipalHoldsSixteenRunningSessions() {
    var registry = stubs().build();
    for (var i = 0; i < 16; i++) {
      startedSession(registry, ALICE);
    }

    var refused = assertThrows(SessionCapacityException.class, () -> create(registry, ALICE));

    assertEquals(Scope.PRINCIPAL, refused.scope());
    assertEquals("sessions of the principal at cap 16", refused.getMessage());
  }

  @Test
  void byDefaultTheRegistryHoldsTwoHundredFiftySixSessions() {
    var registry = stubs().build();
    for (var i = 0; i < 16; i++) {
      var tenant = new Principal("tenant-" + i, "user");
      for (var j = 0; j < 16; j++) {
        create(registry, tenant);
      }
    }

    var refused = assertThrows(SessionCapacityException.class, () -> create(registry, ALICE));

    assertEquals(Scope.SERVER, refused.scope());
    assertEquals("sessions at cap 256", refused.getMessage());
    assertEquals(256, registry.size());
  }

  // ── eviction ─────────────────────────────────────────────────────────────

  @Test
  void aCreateAtThePrincipalCapEvictsItsTerminalSessionAndKeepsItsRunningOne() {
    var registry = stubs().withMaxSessionsPerPrincipal(2).build();
    var terminal = startedSession(registry, ALICE);
    terminal.terminate();
    var running = startedSession(registry, ALICE);

    var fresh = create(registry, ALICE);

    assertEquals(1, terminal.closes.get());
    assertTrue(registry.find(terminal.sessionId(), ALICE).isEmpty());
    assertEquals(0, running.closes.get());
    assertSame(running, registry.find(running.sessionId(), ALICE).orElseThrow());
    assertSame(fresh, registry.find(fresh.sessionId(), ALICE).orElseThrow());
  }

  @Test
  void aCreateAtTheRegistryCapEvictsTheOwnersOwnTerminalSession() {
    var registry = stubs().withMaxSessions(2).build();
    var terminal = create(registry, ALICE);
    terminal.terminate();
    var others = startedSession(registry, EVE);

    create(registry, ALICE);

    assertEquals(1, terminal.closes.get());
    assertEquals(0, others.closes.get());
    assertEquals(2, registry.size());
  }

  @Test
  void aSessionAlreadyTerminalWhenRegisteredMakesRoomForTheNext() {
    var registry =
        stubs()
            .withFactory(
                opts -> {
                  var session = new StubSession(opts.sessionId());
                  session.terminate();
                  return session;
                })
            .withMaxSessionsPerPrincipal(1)
            .build();
    var first = create(registry, ALICE);

    create(registry, ALICE);

    assertEquals(1, first.closes.get());
    assertEquals(1, registry.size());
  }

  @Test
  void aCreateAtThePrincipalCapEvictsThatPrincipalsOldestTerminalSession() {
    var registry = stubs().withMaxSessionsPerPrincipal(2).build();
    var oldest = create(registry, ALICE);
    oldest.terminate();
    now.set(now.get().plusSeconds(1));
    var younger = create(registry, ALICE);
    younger.terminate();

    var fresh = create(registry, ALICE);

    assertEquals(1, oldest.closes.get());
    assertTrue(registry.find(oldest.sessionId(), ALICE).isEmpty());
    assertSame(younger, registry.find(younger.sessionId(), ALICE).orElseThrow());
    assertSame(fresh, registry.find(fresh.sessionId(), ALICE).orElseThrow());
  }

  @Test
  void aCreateNeverEvictsAnotherPrincipalsSessionAndIsRefusedAsIfTheyHeldNone() {
    var registry = stubs().withMaxSessions(2).build();
    var oldest = create(registry, ALICE);
    oldest.terminate();
    now.set(now.get().plusSeconds(1));
    var younger = create(registry, ALICE);
    younger.terminate();
    var ownOnly = stubs().withMaxSessions(2).build();
    create(ownOnly, EVE);
    create(ownOnly, EVE);

    var amongOthers = assertThrows(SessionCapacityException.class, () -> create(registry, EVE));
    var amongOwn = assertThrows(SessionCapacityException.class, () -> create(ownOnly, EVE));

    assertEquals(amongOwn.scope(), amongOthers.scope());
    assertEquals(amongOwn.getMessage(), amongOthers.getMessage());
    assertEquals(0, oldest.closes.get() + younger.closes.get());
    assertEquals(2, registry.size());

    create(registry, ALICE);

    assertEquals(1, oldest.closes.get());
    assertEquals(0, younger.closes.get());
  }

  @Test
  void aCreateWithoutOptionsEvictsNothing() {
    var registry = stubs().withMaxSessionsPerPrincipal(1).build();
    var terminal = create(registry, ALICE);
    terminal.terminate();

    assertThrows(NullPointerException.class, () -> registry.create(null, ALICE));

    assertEquals(0, terminal.closes.get());
    assertSame(terminal, registry.find(terminal.sessionId(), ALICE).orElseThrow());
  }

  @Test
  void anEvictedSessionThatFailsToCloseIsLoggedAndTheCreateStillSucceeds() {
    var registry = stubs().withMaxSessionsPerPrincipal(1).build();
    var broken = create(registry, ALICE);
    broken.onClose =
        () -> {
          throw new AssertionError("close failed");
        };
    broken.terminate();

    try (var log = LogCapture.of(SessionRegistry.class)) {
      create(registry, ALICE);

      var record = log.records().getFirst();
      assertEquals(Level.WARNING, record.getLevel());
      assertEquals(
          "closing unregistered session " + broken.sessionId() + " failed", record.getMessage());
      assertEquals("close failed", record.getThrown().getMessage());
    }
    assertEquals(1, registry.size());
  }

  // ── reservations ─────────────────────────────────────────────────────────

  @Test
  void reserveRejectsANullOwner() {
    var registry = stubs().build();
    var ex = assertThrows(NullPointerException.class, () -> registry.reserve(null));
    assertEquals("owner must not be null", ex.getMessage());
  }

  @Test
  void aReservationHoldsItsPlaceUntilGivenBackOnce() {
    var registry = stubs().withMaxSessions(1).build();
    var reservation = registry.reserve(ALICE);

    assertThrows(SessionCapacityException.class, () -> registry.reserve(EVE));
    reservation.close();
    reservation.close();

    create(registry, EVE);
    assertThrows(SessionCapacityException.class, () -> create(registry, EVE));
  }

  @Test
  void aSpentReservationRegistersNothingMoreAndGivesNothingBack() {
    var registry = stubs().withMaxSessions(1).build();
    var reservation = registry.reserve(ALICE);
    reservation.register(options());

    var ex = assertThrows(IllegalStateException.class, () -> reservation.register(options()));
    reservation.close();

    assertEquals("reservation already spent", ex.getMessage());
    assertEquals(1, registry.size());
    assertThrows(SessionCapacityException.class, () -> create(registry, EVE));
  }

  @Test
  void aReservationGivenBackCannotRegister() {
    var registry = stubs().build();
    var reservation = registry.reserve(ALICE);
    reservation.close();

    var ex = assertThrows(IllegalStateException.class, () -> reservation.register(options()));

    assertEquals("reservation already spent", ex.getMessage());
    assertEquals(0, registry.size());
  }

  @Test
  void registerRejectsNullOptionsAndKeepsTheReservation() {
    var registry = stubs().build();
    var reservation = registry.reserve(ALICE);

    var ex = assertThrows(NullPointerException.class, () -> reservation.register(null));
    reservation.register(options());

    assertEquals("options must not be null", ex.getMessage());
    assertEquals(1, registry.size());
  }

  @Test
  void aFailingFactoryGivesItsReservationBack() {
    var failures = new AtomicInteger(1);
    var registry =
        stubs()
            .withFactory(
                opts -> {
                  if (failures.getAndDecrement() > 0) {
                    throw new IllegalStateException("factory failed");
                  }
                  return new StubSession(opts.sessionId());
                })
            .withMaxSessions(1)
            .build();

    var ex = assertThrows(IllegalStateException.class, () -> create(registry, ALICE));
    create(registry, ALICE);

    assertEquals("factory failed", ex.getMessage());
    assertThrows(SessionCapacityException.class, () -> create(registry, EVE));
  }

  @Test
  void aDuplicateIdGivesItsReservationBack() {
    var registry = stubs().withMaxSessions(2).build();
    var options = options();
    registry.create(options, ALICE);

    var ex = assertThrows(IllegalStateException.class, () -> registry.create(options, EVE));
    create(registry, EVE);

    assertEquals("session id already registered: " + options.sessionId(), ex.getMessage());
    assertThrows(SessionCapacityException.class, () -> create(registry, EVE));
  }

  @Test
  void closingASessionTwiceGivesItsPlaceBackOnce() {
    var registry = stubs().withMaxSessions(1).build();
    var session = create(registry, ALICE);

    assertTrue(registry.close(session.sessionId(), ALICE));
    assertFalse(registry.close(session.sessionId(), ALICE));

    create(registry, EVE);
    assertThrows(SessionCapacityException.class, () -> create(registry, EVE));
    assertEquals(1, session.closes.get());
  }

  @Test
  void aSessionClosedAtWorkKeepsItsCapacityUntilItsResultCompletes() {
    var registry = stubs().withMaxSessions(1).build();
    var working = startedSession(registry, ALICE);

    assertTrue(registry.close(working.sessionId(), ALICE));

    assertEquals(0, registry.size());
    assertTrue(registry.find(working.sessionId(), ALICE).isEmpty());
    var refused = assertThrows(SessionCapacityException.class, () -> create(registry, EVE));
    assertEquals("sessions at cap 1", refused.getMessage());

    working.terminate();

    create(registry, EVE);
    assertThrows(SessionCapacityException.class, () -> create(registry, EVE));
  }

  @Test
  void aSessionClosedAtWorkAndItsSuccessorUnderTheSameIdEachReleaseOnlyTheirOwnCharge() {
    var registry = stubs().withMaxSessions(2).build();
    var options = options();
    var working = (StubSession) registry.create(options, ALICE);
    working.send(UserMessage.text("go"));
    registry.close(working.sessionId(), ALICE);
    var successor = (StubSession) registry.create(options, ALICE);

    working.terminate();

    assertSame(successor, registry.find(options.sessionId(), ALICE).orElseThrow());
    create(registry, EVE);
    var refused = assertThrows(SessionCapacityException.class, () -> create(registry, EVE));
    assertEquals("sessions at cap 2", refused.getMessage());
    assertTrue(registry.close(options.sessionId(), ALICE));
    create(registry, EVE);
    assertThrows(SessionCapacityException.class, () -> create(registry, EVE));
  }

  @Test
  void anUnstartedSessionKeepsItsCapacityUntilItsProviderHasEndedIt() {
    var provider = new HeldEndProvider();
    var registry =
        SessionRegistry.newBuilder()
            .withClock(now::get)
            .withMaxSessions(1)
            .withMaxSessionsPerPrincipal(1)
            .build();
    var first = registry.create(withProvider(provider), ALICE);
    var closing = CompletableFuture.supplyAsync(() -> registry.close(first.sessionId(), ALICE));
    try {
      Await.latch("the first session's provider cleanup", provider.ending);

      var refused =
          assertThrows(
              SessionCapacityException.class, () -> registry.create(withProvider(provider), ALICE));
      assertEquals("sessions of the principal at cap 1", refused.getMessage());
      assertFalse(first.result().isDone());
    } finally {
      provider.release.countDown();
    }
    assertTrue(Await.value("the close of the first session", closing));
    registry.create(withProvider(provider), ALICE);
    assertEquals(2, provider.started.get());
    registry.closeAll();
  }

  @Test
  void aSessionThatFailsToCloseIsStillUnregisteredAndTheFailureLogged() {
    var registry = stubs().withMaxSessions(1).build();
    var broken = create(registry, ALICE);
    broken.onClose =
        () -> {
          throw new IllegalStateException("close failed");
        };

    try (var log = LogCapture.of(SessionRegistry.class)) {
      assertTrue(registry.close(broken.sessionId(), ALICE));

      assertEquals(1, log.records().size());
    }
    create(registry, EVE);
  }

  @Test
  void aPrincipalThatHoldsNothingMoreLeavesNoEntryBehind() {
    var quota = new SessionQuota(4, 2);
    quota.reserve(ALICE);
    quota.reserve(ALICE);
    quota.reserve(EVE);

    quota.release(ALICE);
    quota.release(ALICE);

    assertEquals(1, quota.principalsForTests());
    quota.release(EVE);
    assertEquals(0, quota.principalsForTests());
  }

  // ── fixtures ─────────────────────────────────────────────────────────────

  private SessionRegistry.Builder stubs() {
    return SessionRegistry.newBuilder()
        .withFactory(opts -> new StubSession(opts.sessionId()))
        .withClock(now::get);
  }

  private StubSession create(SessionRegistry registry, Principal owner) {
    return (StubSession) registry.create(options(), owner);
  }

  private StubSession startedSession(SessionRegistry registry, Principal owner) {
    var session = create(registry, owner);
    session.send(UserMessage.text("go"));
    return session;
  }

  private SessionOptions withProvider(ExecutionProvider provider) {
    return SessionOptions.newBuilder()
        .withModel(ScriptedModel.newBuilder().withTextTurn("x").build())
        .withSessionId("s" + ids.incrementAndGet())
        .withExecutionProvider(provider)
        .build();
  }

  /** Counts the sessions it starts and holds the first session's end until released. */
  private static final class HeldEndProvider implements ExecutionProvider {

    private final AtomicInteger started = new AtomicInteger();
    private final CountDownLatch ending = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public Set<Runtime> supportedRuntimes() {
      return Set.of();
    }

    @Override
    public SessionStartOutcome onSessionStart(SessionContext ctx) {
      started.incrementAndGet();
      return SessionStartOutcome.accept();
    }

    @Override
    public void onSessionEnd(SessionContext ctx) {
      ending.countDown();
      Await.latch("the test to release the session's end", release);
    }

    @Override
    public ExecutionResult execute(
        SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
      throw new AssertionError("not used");
    }
  }

  private SessionOptions options() {
    return SessionOptions.newBuilder()
        .withModel(ScriptedModel.newBuilder().withTextTurn("x").build())
        .withSessionId("s" + ids.incrementAndGet())
        .build();
  }
}
