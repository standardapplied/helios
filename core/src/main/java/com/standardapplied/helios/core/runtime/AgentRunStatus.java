/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

/**
 * Lifecycle status of a durable agent run.
 *
 * <ul>
 *   <li>{@link #RUNNING} — the run is checkpointed and an executor is actively iterating it.
 *   <li>{@link #SUSPENDED} — execution stopped without a clean terminal status (typically a JVM
 *       crash or interruption); the run is eligible for {@code Agent.resume(...)}.
 *   <li>{@link #COMPLETED} — the run finished normally with a final response.
 *   <li>{@link #FAILED} — the run terminated with an error and is not resumable.
 * </ul>
 */
public enum AgentRunStatus {
  RUNNING,
  SUSPENDED,
  COMPLETED,
  FAILED
}
