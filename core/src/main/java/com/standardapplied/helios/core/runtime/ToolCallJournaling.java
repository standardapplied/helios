/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.runtime;

import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Journals tool calls through the {@link ToolCallJournal} of a {@link Durability} bundle: the
 * {@link ToolCallStatus#STARTED} entry before a tool runs, its terminal status after, and the
 * in-flight entries a resume reconciles. Run checkpointing is {@link DurabilityCoordinator}'s.
 *
 * <p>Failures from the journal are caught and logged at {@code WARNING}: durability is best-effort
 * relative to the user's request, and a Postgres blip while journaling must never abort an
 * otherwise-successful agent run.
 */
public final class ToolCallJournaling {

  private static final Logger LOG = Logger.getLogger(ToolCallJournaling.class.getName());

  private final Durability durability;

  public ToolCallJournaling(Durability durability) {
    this.durability = Objects.requireNonNull(durability, "durability");
  }

  /**
   * Insert a {@link ToolCallStatus#STARTED} entry. Returns {@code true} when a journal row was
   * written so the caller knows whether to write the matching terminal status; failures and
   * non-durable runs (a {@code null} run id) both return {@code false}.
   */
  public boolean start(
      UUID runId, int iteration, String toolCallId, String toolName, Map<String, Object> args) {
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
   * Write the terminal journal status for a tool call: completed when the result succeeded, failed
   * otherwise. Failures are logged but never propagated: the tool already executed and the user is
   * owed its result. An orphaned {@code STARTED} entry will be reconciled on the next resume.
   */
  public void complete(UUID runId, String toolCallId, ToolResult toolResult) {
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
  public void fail(UUID runId, String toolCallId, String error) {
    try {
      durability.toolCallJournal().fail(runId, toolCallId, error);
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "Tool-call journal failure-write failed; original tool exception will still propagate",
          e);
    }
  }

  /** The run's journal entries still {@link ToolCallStatus#STARTED}. */
  public List<ToolCallRecord> inflight(UUID runId) {
    return durability.toolCallJournal().inflight(runId);
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
}
