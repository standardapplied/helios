/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Targeted coverage for {@link DurabilityCoordinator}. The agent and workflow durability tests
 * exercise the happy paths; this class focuses on the guard branches and best-effort error paths
 * that those higher-level tests never reach.
 */
class DurabilityCoordinatorTest {

  @Test
  void constructorRejectsNullDurability() {
    assertThrows(NullPointerException.class, () -> new DurabilityCoordinator(null, "agent"));
  }

  @Test
  void constructorRejectsNullAgentName() {
    var durability = Durability.inMemory();
    assertThrows(NullPointerException.class, () -> new DurabilityCoordinator(durability, null));
  }

  @Test
  void durabilityAccessorReturnsBundle() {
    var durability = Durability.inMemory();
    var coord = new DurabilityCoordinator(durability, "agent");
    assertEquals(durability, coord.durability());
  }

  @Test
  void initializeWithNullRunIdIsNoOp() {
    var store = new RecordingRunStore();
    var coord =
        new DurabilityCoordinator(
            Durability.newBuilder()
                .withRunStore(store)
                .withToolCallJournal(new InMemoryToolCallJournal())
                .build(),
            "agent");

    coord.initialize(null, UUID.randomUUID(), "alice", 0);

    assertEquals(0, store.checkpointCount());
  }

  @Test
  void checkpointWithNullRunIdIsNoOp() {
    var store = new RecordingRunStore();
    var coord =
        new DurabilityCoordinator(
            Durability.newBuilder()
                .withRunStore(store)
                .withToolCallJournal(new InMemoryToolCallJournal())
                .build(),
            "agent");

    coord.checkpoint(null, UUID.randomUUID(), "alice", 1);

    assertEquals(0, store.checkpointCount());
  }

  @Test
  void checkpointFrequencyGreaterThanOneSkipsNonAlignedIterations() {
    var store = new RecordingRunStore();
    var coord =
        new DurabilityCoordinator(
            Durability.newBuilder()
                .withRunStore(store)
                .withToolCallJournal(new InMemoryToolCallJournal())
                .withCheckpointFrequency(3)
                .build(),
            "agent");
    var runId = UUID.randomUUID();

    coord.checkpoint(runId, null, "alice", 0); // 0 % 3 == 0 → write
    coord.checkpoint(runId, null, "alice", 1); // skip
    coord.checkpoint(runId, null, "alice", 2); // skip
    coord.checkpoint(runId, null, "alice", 3); // 3 % 3 == 0 → write
    coord.checkpoint(runId, null, "alice", 4); // skip
    coord.checkpoint(runId, null, "alice", 6); // 6 % 3 == 0 → write

    assertEquals(3, store.checkpointCount());
  }

  @Test
  void completeWithNullRunIdIsNoOp() {
    var store = new RecordingRunStore();
    var coord =
        new DurabilityCoordinator(
            Durability.newBuilder()
                .withRunStore(store)
                .withToolCallJournal(new InMemoryToolCallJournal())
                .build(),
            "agent");

    coord.complete(null, UUID.randomUUID(), "alice", 5);

    assertEquals(0, store.checkpointCount());
  }

  @Test
  void failWithNullRunIdIsNoOp() {
    var store = new RecordingRunStore();
    var coord =
        new DurabilityCoordinator(
            Durability.newBuilder()
                .withRunStore(store)
                .withToolCallJournal(new InMemoryToolCallJournal())
                .build(),
            "agent");

    coord.fail(null, UUID.randomUUID(), "alice", 5, "anything");

    assertEquals(0, store.checkpointCount());
  }

  @Test
  void lifecycleWritesEachStatusOfTheRun() {
    var coord = new DurabilityCoordinator(Durability.inMemory(), "agent");
    var runId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();

    coord.initialize(runId, sessionId, "alice", 0);
    var running = coord.findRun(runId).orElseThrow();
    coord.complete(runId, sessionId, "alice", 3);
    var completed = coord.findRun(runId).orElseThrow();
    coord.fail(runId, sessionId, "alice", 4, "model unavailable");
    var failed = coord.findRun(runId).orElseThrow();
    coord.markSuspended(failed);
    var suspended = coord.findRun(runId).orElseThrow();

    assertEquals(AgentRunStatus.RUNNING, running.status());
    assertEquals("agent", running.agentId());
    assertEquals(sessionId, running.sessionId());
    assertNull(running.endedAt());
    assertEquals(AgentRunStatus.COMPLETED, completed.status());
    assertEquals(3, completed.iteration());
    assertNotNull(completed.endedAt());
    assertEquals(AgentRunStatus.FAILED, failed.status());
    assertEquals("model unavailable", failed.error());
    assertEquals(AgentRunStatus.SUSPENDED, suspended.status());
  }

  @Test
  void safeCheckpointSwallowsRuntimeException() {
    var coord =
        new DurabilityCoordinator(
            Durability.newBuilder()
                .withRunStore(new ThrowingRunStore())
                .withToolCallJournal(new InMemoryToolCallJournal())
                .build(),
            "agent");

    // initialize → safeCheckpoint internally; must not bubble the store's RuntimeException.
    coord.initialize(UUID.randomUUID(), null, "alice", 0);
  }

  @Test
  void findRunDelegatesToRunStore() {
    var coord = new DurabilityCoordinator(Durability.inMemory(), "agent");
    assertNotNull(coord.findRun(UUID.randomUUID()));
  }

  // --- Test doubles ---------------------------------------------------------------------------

  private static final class RecordingRunStore implements RunStore {
    private final AtomicInteger checkpoints = new AtomicInteger();
    private final InMemoryRunStore delegate = new InMemoryRunStore();

    int checkpointCount() {
      return checkpoints.get();
    }

    @Override
    public void checkpoint(AgentRun run) {
      checkpoints.incrementAndGet();
      delegate.checkpoint(run);
    }

    @Override
    public Optional<AgentRun> find(UUID runId) {
      return delegate.find(runId);
    }

    @Override
    public List<AgentRun> findByStatus(AgentRunStatus status) {
      return delegate.findByStatus(status);
    }

    @Override
    public int purgeOlderThan(java.time.Duration olderThan) {
      return delegate.purgeOlderThan(olderThan);
    }
  }

  private static final class ThrowingRunStore implements RunStore {
    @Override
    public void checkpoint(AgentRun run) {
      throw new RuntimeException("boom");
    }

    @Override
    public Optional<AgentRun> find(UUID runId) {
      throw new RuntimeException("boom");
    }

    @Override
    public List<AgentRun> findByStatus(AgentRunStatus status) {
      return List.of();
    }

    @Override
    public int purgeOlderThan(java.time.Duration olderThan) {
      return 0;
    }
  }
}
