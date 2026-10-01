/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import ai.singlr.core.common.Ids;
import ai.singlr.core.tool.ToolResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mediator between the agent loop and the {@link Durability} bundle. Encapsulates checkpointing,
 * tool-call journaling, and the "load existing or seed new" lifecycle of an {@link AgentRun}.
 *
 * <p>Every public method is a no-op when invoked with a state lacking a run id — callers can wire
 * the coordinator unconditionally and the right behavior happens whether or not a particular run is
 * durable.
 *
 * <p>Failures from the underlying {@link RunStore} or {@link ToolCallJournal} are caught and logged
 * at {@code WARNING}: durability is best-effort relative to the user's request, and a Postgres blip
 * during a checkpoint must never abort an otherwise-successful agent run.
 */
public class DurabilityCoordinator {

  private static final Logger LOG = Logger.getLogger(DurabilityCoordinator.class.getName());

  private final Durability durability;
  private final String agentName;

  public DurabilityCoordinator(Durability durability, String agentName) {
    this.durability = Objects.requireNonNull(durability, "durability");
    this.agentName = Objects.requireNonNull(agentName, "agentName");
  }

  public Durability durability() {
    return durability;
  }

  // --- Run lifecycle ---

  /** Write the initial {@link AgentRunStatus#RUNNING} checkpoint. Idempotent. */
  public void initialize(UUID runId, UUID sessionId, String userId, int iteration) {
    if (runId == null) {
      return;
    }
    safeCheckpoint(
        buildRow(runId, sessionId, userId, iteration, AgentRunStatus.RUNNING, false, null),
        "initialize");
  }

  /**
   * Update the iteration counter and last-checkpoint time on an in-progress run. Honors {@link
   * Durability#checkpointFrequency()} — when set to {@code n} (default 1), only iterations where
   * {@code iteration % n == 0} write a checkpoint. The first iteration always writes (initialize
   * has already fired for iteration 0; the frequency gate matches there too because {@code 0 % n ==
   * 0}). Terminal states are unaffected.
   */
  public void checkpoint(UUID runId, UUID sessionId, String userId, int iteration) {
    if (runId == null) {
      return;
    }
    if (iteration % durability.checkpointFrequency() != 0) {
      return;
    }
    safeCheckpoint(
        buildRow(runId, sessionId, userId, iteration, AgentRunStatus.RUNNING, false, null),
        "checkpoint");
  }

  /** Mark the run {@link AgentRunStatus#COMPLETED} with an end timestamp. */
  public void complete(UUID runId, UUID sessionId, String userId, int iteration) {
    if (runId == null) {
      return;
    }
    safeCheckpoint(
        buildRow(runId, sessionId, userId, iteration, AgentRunStatus.COMPLETED, true, null),
        "completed");
  }

  /** Mark the run {@link AgentRunStatus#FAILED} with the given error message. */
  public void fail(UUID runId, UUID sessionId, String userId, int iteration, String error) {
    if (runId == null) {
      return;
    }
    safeCheckpoint(
        buildRow(runId, sessionId, userId, iteration, AgentRunStatus.FAILED, true, error),
        "failed");
  }

  /**
   * Build the row to send to the store. We rely on the {@code RunStore.checkpoint} contract to do
   * an upsert that preserves {@code started_at} on conflict — so we never have to do a SELECT
   * before write to learn the original start time. This halves the per-iteration DB cost for
   * Postgres-backed durable runs (was SELECT-then-UPSERT, now single UPSERT).
   *
   * <p>The {@code agent_id}, {@code session_id}, and {@code user_id} fields are stable for the
   * lifetime of a run, so the UPSERT's {@code DO UPDATE SET} overwriting them with the same values
   * is a semantic no-op.
   */
  private AgentRun buildRow(
      UUID runId,
      UUID sessionId,
      String userId,
      int iteration,
      AgentRunStatus status,
      boolean terminal,
      String error) {
    var now = Ids.now();
    var builder =
        AgentRun.newBuilder()
            .withRunId(runId)
            .withSessionId(sessionId)
            .withAgentId(agentName)
            .withUserId(userId)
            .withStatus(status)
            .withIteration(iteration)
            .withStartedAt(now)
            .withLastCheckpointAt(now);
    if (terminal) {
      builder.withEndedAt(now);
    }
    if (error != null) {
      builder.withError(error);
    }
    return builder.build();
  }

  // --- Tool-call journaling ---

  /**
   * Insert a {@link ToolCallStatus#STARTED} entry. Returns {@code true} when a journal row was
   * written so the caller knows whether to write the matching terminal status; failures and
   * non-durable runs both return {@code false}.
   */
  public boolean journalStart(
      UUID runId,
      int iteration,
      String toolCallId,
      String toolName,
      java.util.Map<String, Object> args) {
    if (runId == null) {
      return false;
    }
    var record =
        ToolCallRecord.newBuilder()
            .withRunId(runId)
            .withIteration(iteration)
            .withToolCallId(toolCallId)
            .withToolName(toolName)
            .withArgs(args)
            .withStatus(ToolCallStatus.STARTED)
            .withStartedAt(Ids.now())
            .build();
    try {
      durability.toolCallJournal().start(record);
      return true;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Tool-call journal start failed; continuing without journal", e);
      return false;
    }
  }

  /**
   * Write the terminal journal status for a tool call. Failures are logged but never propagated:
   * the tool already executed and the user is owed its result. An orphaned {@code STARTED} entry
   * will be reconciled on the next resume.
   */
  public void journalTerminal(UUID runId, String toolCallId, ToolResult toolResult) {
    try {
      if (toolResult.success()) {
        durability.toolCallJournal().complete(runId, toolCallId, toolResult.output());
      } else {
        durability.toolCallJournal().fail(runId, toolCallId, toolResult.output());
      }
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "Tool-call journal terminal write failed; tool result preserved, journal entry"
              + " left STARTED",
          e);
    }
  }

  /** Write a terminal {@code FAILED} entry — used by the {@code throws} path of tool execution. */
  public void journalTerminalFailure(UUID runId, String toolCallId, String error) {
    try {
      durability.toolCallJournal().fail(runId, toolCallId, error);
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "Tool-call journal failure-write failed; original tool exception will still propagate",
          e);
    }
  }

  // --- Resume primitives ---

  public Optional<AgentRun> findRun(UUID runId) {
    return durability.runStore().find(runId);
  }

  public List<ToolCallRecord> inflightFor(UUID runId) {
    return durability.toolCallJournal().inflight(runId);
  }

  /** Mark the run {@link AgentRunStatus#SUSPENDED} — used when refusing to resume. */
  public void markSuspended(AgentRun run) {
    safeCheckpoint(
        AgentRun.newBuilder(run).withStatus(AgentRunStatus.SUSPENDED).build(), "markSuspended");
  }

  /**
   * Best-effort transition of an in-flight journal entry to {@code FAILED} with a synthetic reason.
   * Used during resume preparation to clear the way for replay.
   */
  public void markInflightFailed(UUID runId, String toolCallId, String reason) {
    try {
      durability.toolCallJournal().fail(runId, toolCallId, reason);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, () -> "Failed to mark inflight entry " + toolCallId + " failed");
      LOG.log(Level.FINE, "mark-failed exception", e);
    }
  }

  private void safeCheckpoint(AgentRun run, String where) {
    try {
      durability.runStore().checkpoint(run);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, () -> "RunStore.checkpoint failed in " + where);
      LOG.log(Level.FINE, "checkpoint exception", e);
    }
  }
}
