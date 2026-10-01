/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

/**
 * Lifecycle status of a journaled tool call within a durable run.
 *
 * <p>{@link #STARTED} means the journal entry was written before the tool was invoked but no
 * terminal status was recorded — on resume, this signals an in-flight call at the time of crash.
 * Whether such a call is safely replayable is governed by the tool's idempotency flag and the
 * {@link UnsafeResumePolicy} configured on the agent.
 */
public enum ToolCallStatus {
  STARTED,
  SUCCEEDED,
  FAILED
}
