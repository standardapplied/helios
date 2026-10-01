/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import ai.singlr.core.common.Ids;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ToolCallJournal}. Records are stored in per-run lists keyed by run id;
 * concurrent appends from parallel tool-execution threads are guarded via per-list intrinsic locks
 * ({@code synchronized (bucket)}).
 */
public class InMemoryToolCallJournal implements ToolCallJournal {

  private final Map<UUID, List<ToolCallRecord>> records = new ConcurrentHashMap<>();

  @Override
  public void start(ToolCallRecord record) {
    Objects.requireNonNull(record, "record");
    var bucket = records.computeIfAbsent(record.runId(), k -> new ArrayList<>());
    synchronized (bucket) {
      for (var existing : bucket) {
        if (existing.toolCallId().equals(record.toolCallId())) {
          throw new IllegalStateException(
              "Duplicate tool-call journal entry for run "
                  + record.runId()
                  + ": "
                  + record.toolCallId());
        }
      }
      bucket.add(record);
    }
  }

  @Override
  public void complete(UUID runId, String toolCallId, String output) {
    transitionTerminal(runId, toolCallId, ToolCallStatus.SUCCEEDED, output, null);
  }

  @Override
  public void fail(UUID runId, String toolCallId, String error) {
    transitionTerminal(runId, toolCallId, ToolCallStatus.FAILED, null, error);
  }

  @Override
  public List<ToolCallRecord> inflight(UUID runId) {
    var bucket = records.get(runId);
    if (bucket == null) {
      return List.of();
    }
    var out = new ArrayList<ToolCallRecord>();
    synchronized (bucket) {
      for (var rec : bucket) {
        if (rec.status() == ToolCallStatus.STARTED) {
          out.add(rec);
        }
      }
    }
    return List.copyOf(out);
  }

  @Override
  public List<ToolCallRecord> all(UUID runId) {
    var bucket = records.get(runId);
    if (bucket == null) {
      return List.of();
    }
    var out = new ArrayList<ToolCallRecord>();
    synchronized (bucket) {
      out.addAll(bucket);
    }
    out.sort(Comparator.comparing(ToolCallRecord::startedAt));
    return List.copyOf(out);
  }

  private void transitionTerminal(
      UUID runId, String toolCallId, ToolCallStatus terminal, String output, String error) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(toolCallId, "toolCallId");
    var bucket = records.get(runId);
    if (bucket == null) {
      return;
    }
    synchronized (bucket) {
      for (int i = 0; i < bucket.size(); i++) {
        var rec = bucket.get(i);
        if (rec.toolCallId().equals(toolCallId) && rec.status() == ToolCallStatus.STARTED) {
          bucket.set(
              i,
              ToolCallRecord.newBuilder(rec)
                  .withStatus(terminal)
                  .withOutput(output)
                  .withError(error)
                  .withEndedAt(Ids.now())
                  .build());
          return;
        }
      }
    }
  }
}
