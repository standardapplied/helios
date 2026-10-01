/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import java.util.List;
import java.util.UUID;

/**
 * Append-and-update journal of tool invocations within a durable {@link AgentRun}. Implementations
 * must be safe for concurrent use across the agent loop's tool-execution paths (sequential and
 * parallel-on-virtual-threads).
 *
 * <p>Lifecycle per call:
 *
 * <ol>
 *   <li>Agent calls {@link #start} immediately before entering the {@link
 *       ai.singlr.core.fault.FaultTolerance} envelope around the tool.
 *   <li>One of {@link #complete} or {@link #fail} fires after the envelope returns. A JVM crash
 *       between (1) and (2) leaves the entry as {@link ToolCallStatus#STARTED}; on resume, {@link
 *       #inflight} surfaces it.
 * </ol>
 */
public interface ToolCallJournal {

  /** Insert a new {@link ToolCallStatus#STARTED} entry. */
  void start(ToolCallRecord record);

  /**
   * Transition a {@link ToolCallStatus#STARTED} entry to {@link ToolCallStatus#SUCCEEDED} with the
   * given output text.
   */
  void complete(UUID runId, String toolCallId, String output);

  /**
   * Transition a {@link ToolCallStatus#STARTED} entry to {@link ToolCallStatus#FAILED} with the
   * given error message.
   */
  void fail(UUID runId, String toolCallId, String error);

  /**
   * Return all entries for a run that are still {@link ToolCallStatus#STARTED} — i.e., journaled
   * before the tool was invoked but never reached a terminal status. {@code Agent.resume(...)}
   * inspects this list to decide whether the run is safe to continue.
   */
  List<ToolCallRecord> inflight(UUID runId);

  /** Return every journal entry for a run, in start order. Useful for forensics. */
  List<ToolCallRecord> all(UUID runId);
}
