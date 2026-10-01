/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
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
  void journalStartWithNullRunIdReturnsFalse() {
    var coord = new DurabilityCoordinator(Durability.inMemory(), "agent");

    var wrote = coord.journalStart(null, 0, "tcid", "tool", Map.of());

    assertFalse(wrote);
  }

  @Test
  void journalStartSurvivesRuntimeException() {
    var coord =
        new DurabilityCoordinator(
            Durability.newBuilder()
                .withRunStore(new InMemoryRunStore())
                .withToolCallJournal(new ThrowingToolCallJournal())
                .build(),
            "agent");

    assertFalse(coord.journalStart(UUID.randomUUID(), 0, "tcid", "tool", Map.of()));
  }

  @Test
  void journalTerminalSuccessRoutesToComplete() {
    var journal = new RecordingToolCallJournal();
    var coord =
        new DurabilityCoordinator(
            Durability.newBuilder()
                .withRunStore(new InMemoryRunStore())
                .withToolCallJournal(journal)
                .build(),
            "agent");

    var runId = UUID.randomUUID();
    coord.journalTerminal(runId, "tcid", ToolResult.success("ok"));

    assertEquals(1, journal.completeCount());
    assertEquals(0, journal.failCount());
  }

  @Test
  void journalTerminalFailureRoutesToFail() {
    var journal = new RecordingToolCallJournal();
    var coord =
        new DurabilityCoordinator(
            Durability.newBuilder()
                .withRunStore(new InMemoryRunStore())
                .withToolCallJournal(journal)
                .build(),
            "agent");

    var runId = UUID.randomUUID();
    coord.journalTerminal(runId, "tcid", ToolResult.failure("boom"));

    assertEquals(0, journal.completeCount());
    assertEquals(1, journal.failCount());
  }

  @Test
  void journalTerminalSwallowsRuntimeException() {
    var coord =
        new DurabilityCoordinator(
            Durability.newBuilder()
                .withRunStore(new InMemoryRunStore())
                .withToolCallJournal(new ThrowingToolCallJournal())
                .build(),
            "agent");

    // Must not throw — the tool already executed and the caller is owed its result.
    coord.journalTerminal(UUID.randomUUID(), "tcid", ToolResult.success("ok"));
  }

  @Test
  void journalTerminalFailureWritesFailEntry() {
    var journal = new RecordingToolCallJournal();
    var coord =
        new DurabilityCoordinator(
            Durability.newBuilder()
                .withRunStore(new InMemoryRunStore())
                .withToolCallJournal(journal)
                .build(),
            "agent");

    coord.journalTerminalFailure(UUID.randomUUID(), "tcid", "exception path");

    assertEquals(1, journal.failCount());
  }

  @Test
  void journalTerminalFailureSwallowsRuntimeException() {
    var coord =
        new DurabilityCoordinator(
            Durability.newBuilder()
                .withRunStore(new InMemoryRunStore())
                .withToolCallJournal(new ThrowingToolCallJournal())
                .build(),
            "agent");

    // Must not throw — original exception path needs to keep propagating outward.
    coord.journalTerminalFailure(UUID.randomUUID(), "tcid", "boom");
  }

  @Test
  void markInflightFailedSwallowsRuntimeException() {
    var coord =
        new DurabilityCoordinator(
            Durability.newBuilder()
                .withRunStore(new InMemoryRunStore())
                .withToolCallJournal(new ThrowingToolCallJournal())
                .build(),
            "agent");

    coord.markInflightFailed(UUID.randomUUID(), "tcid", "synthetic reason");
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

  @Test
  void inflightForDelegatesToJournal() {
    var coord = new DurabilityCoordinator(Durability.inMemory(), "agent");
    assertNotNull(coord.inflightFor(UUID.randomUUID()));
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

  private static final class RecordingToolCallJournal implements ToolCallJournal {
    private final AtomicInteger completes = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final InMemoryToolCallJournal delegate = new InMemoryToolCallJournal();

    int completeCount() {
      return completes.get();
    }

    int failCount() {
      return failures.get();
    }

    @Override
    public void start(ToolCallRecord record) {
      delegate.start(record);
    }

    @Override
    public void complete(UUID runId, String toolCallId, String output) {
      completes.incrementAndGet();
      delegate.complete(runId, toolCallId, output);
    }

    @Override
    public void fail(UUID runId, String toolCallId, String error) {
      failures.incrementAndGet();
      delegate.fail(runId, toolCallId, error);
    }

    @Override
    public List<ToolCallRecord> inflight(UUID runId) {
      return delegate.inflight(runId);
    }

    @Override
    public List<ToolCallRecord> all(UUID runId) {
      return delegate.all(runId);
    }
  }

  private static final class ThrowingToolCallJournal implements ToolCallJournal {
    @Override
    public void start(ToolCallRecord record) {
      throw new RuntimeException("boom-start");
    }

    @Override
    public void complete(UUID runId, String toolCallId, String output) {
      throw new RuntimeException("boom-complete");
    }

    @Override
    public void fail(UUID runId, String toolCallId, String error) {
      throw new RuntimeException("boom-fail");
    }

    @Override
    public List<ToolCallRecord> inflight(UUID runId) {
      return List.of();
    }

    @Override
    public List<ToolCallRecord> all(UUID runId) {
      return List.of();
    }
  }
}
