/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable store for {@link AgentRun} records. Implementations must be safe for concurrent use:
 * checkpoints from one thread and reads from another should not corrupt or lose state.
 *
 * <p>The {@link InMemoryRunStore} is suitable for tests and single-process deployments without
 * crash recovery requirements; use the Postgres-backed implementation in {@code helios-persistence}
 * for production durability.
 *
 * <p>Distributed multi-JVM coordination (worker leases, claim-by-skip-locked) is intentionally not
 * part of this interface in v1 — additive shape changes can introduce it without breaking
 * single-JVM users.
 */
public interface RunStore {

  /**
   * Upsert the run keyed by {@link AgentRun#runId()}. On an existing row implementations MUST
   * preserve {@link AgentRun#startedAt()} from the stored row — the {@link DurabilityCoordinator}
   * calls {@code checkpoint} repeatedly with {@code startedAt} set to the current time because it
   * has no cheap way to remember the original. Every other field (status, iteration,
   * lastCheckpointAt, endedAt, error, session/agent/user ids) is overwritten with the incoming
   * value. This halves per-iteration DB cost in the Postgres backend (no SELECT-before-UPSERT).
   */
  void checkpoint(AgentRun run);

  /** Look up a run by id. */
  Optional<AgentRun> find(UUID runId);

  /**
   * List runs in a given status, newest first. The implementation may bound the result; callers
   * should treat this as a recent-window view, not a full audit.
   */
  List<AgentRun> findByStatus(AgentRunStatus status);

  /**
   * Delete terminal runs ({@link AgentRunStatus#COMPLETED} or {@link AgentRunStatus#FAILED}) whose
   * {@code endedAt} is older than {@code now - olderThan}. Cascades to any {@link ToolCallJournal}
   * entries associated with those runs.
   *
   * <p>Use this for routine retention sweeps (e.g. via Helidon scheduling): completed runs
   * shouldn't accumulate forever. Pass a long-enough window (typically days or weeks) that
   * recently-resumed runs aren't accidentally pruned.
   *
   * @return the number of runs deleted
   * @throws NullPointerException if {@code olderThan} is null
   * @throws IllegalArgumentException if {@code olderThan} is negative
   */
  int purgeOlderThan(Duration olderThan);
}
