/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import ai.singlr.core.common.Ids;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link RunStore}. State lives in a {@link ConcurrentHashMap} keyed by run id; suitable
 * for tests and single-process deployments without crash-recovery requirements.
 */
public class InMemoryRunStore implements RunStore {

  private final Map<UUID, AgentRun> runs = new ConcurrentHashMap<>();

  @Override
  public void checkpoint(AgentRun run) {
    Objects.requireNonNull(run, "run");
    // Match the Postgres UPSERT contract: when a row already exists for this run_id, preserve the
    // original startedAt (the agent loop calls checkpoint repeatedly with startedAt set to "now"
    // because it has no cheap way to know the original; the store does the bookkeeping).
    runs.merge(
        run.runId(),
        run,
        (existing, incoming) ->
            AgentRun.newBuilder(incoming).withStartedAt(existing.startedAt()).build());
  }

  @Override
  public Optional<AgentRun> find(UUID runId) {
    if (runId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(runs.get(runId));
  }

  @Override
  public List<AgentRun> findByStatus(AgentRunStatus status) {
    Objects.requireNonNull(status, "status");
    var matches = new ArrayList<AgentRun>();
    for (var run : runs.values()) {
      if (run.status() == status) {
        matches.add(run);
      }
    }
    matches.sort(Comparator.comparing(AgentRun::lastCheckpointAt).reversed());
    return List.copyOf(matches);
  }

  @Override
  public int purgeOlderThan(Duration olderThan) {
    Objects.requireNonNull(olderThan, "olderThan");
    if (olderThan.isNegative()) {
      throw new IllegalArgumentException("olderThan must be non-negative");
    }
    var cutoff = Ids.now().minus(olderThan);
    int deleted = 0;
    Iterator<Map.Entry<UUID, AgentRun>> it = runs.entrySet().iterator();
    while (it.hasNext()) {
      var run = it.next().getValue();
      if (isTerminal(run.status()) && run.endedAt() != null && run.endedAt().isBefore(cutoff)) {
        it.remove();
        deleted++;
      }
    }
    return deleted;
  }

  private static boolean isTerminal(AgentRunStatus status) {
    return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED;
  }
}
