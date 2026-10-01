/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

/**
 * Per-session concurrency caps the agent loop enforces via bounded queues and semaphores.
 *
 * <p>Four caps, one per work axis. The loop creates one {@code Semaphore} per cap at session start;
 * tools acquire-release around their dispatch. Backpressure is per-axis: a thundering herd of file
 * writes does not block tool calls in another category.
 *
 * <p>Defaults track Helios production values:
 *
 * <ul>
 *   <li>{@code maxConcurrentToolCalls}: 32 — virtual threads are cheap, but the bound prevents a
 *       runaway model from spawning thousands of in-flight tool dispatches.
 *   <li>{@code maxConcurrentFileWrites}: 4 — writes serialize on the filesystem and on the
 *       FileTracker; over-parallelising offers no throughput gain and inflates lock contention.
 *   <li>{@code maxConcurrentExecutions}: 2 — code-execution providers (JShell, bash, python) hold
 *       subprocesses with measurable boot cost; two is enough for a long SQL + parallel Python
 *       aggregation, the canonical concurrent shape.
 *   <li>{@code maxQueuedUserMessages}: 256 — bounded steering queue capacity; over-full triggers
 *       HTTP 429 at the runtime layer rather than silently dropping.
 * </ul>
 *
 * <p>Process-level caps ({@code maxConcurrentSessions}, {@code maxConcurrentModelCalls}) live on
 * {@code helios-runtime}, not here — they are deployment policy, not session policy.
 *
 * @param maxConcurrentToolCalls semaphore cap for tool dispatch; must be positive
 * @param maxConcurrentFileWrites semaphore cap for write-category tool dispatch; must be positive
 * @param maxConcurrentExecutions semaphore cap for code-execution dispatch; must be positive
 * @param maxQueuedUserMessages capacity of the per-session {@link SteeringQueue}; must be positive
 */
public record ConcurrencyLimits(
    int maxConcurrentToolCalls,
    int maxConcurrentFileWrites,
    int maxConcurrentExecutions,
    int maxQueuedUserMessages) {

  private static final ConcurrencyLimits DEFAULTS = new ConcurrencyLimits(32, 4, 2, 256);

  /**
   * Canonical constructor.
   *
   * @throws IllegalArgumentException if any cap is not strictly positive
   */
  public ConcurrencyLimits {
    if (maxConcurrentToolCalls <= 0) {
      throw new IllegalArgumentException(
          "maxConcurrentToolCalls must be positive, got " + maxConcurrentToolCalls);
    }
    if (maxConcurrentFileWrites <= 0) {
      throw new IllegalArgumentException(
          "maxConcurrentFileWrites must be positive, got " + maxConcurrentFileWrites);
    }
    if (maxConcurrentExecutions <= 0) {
      throw new IllegalArgumentException(
          "maxConcurrentExecutions must be positive, got " + maxConcurrentExecutions);
    }
    if (maxQueuedUserMessages <= 0) {
      throw new IllegalArgumentException(
          "maxQueuedUserMessages must be positive, got " + maxQueuedUserMessages);
    }
  }

  /**
   * Returns the shared default-limits singleton.
   *
   * @return the production defaults documented in the class Javadoc
   */
  public static ConcurrencyLimits defaults() {
    return DEFAULTS;
  }
}
