/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

import java.util.Map;

/**
 * Aggregated metrics for one group of traces produced by a rollup query. The {@code key} maps each
 * grouping dimension to its value for this group (e.g., {@code {groupId: "eval-batch-1"}} or {@code
 * {promptName: "analytics", promptVersion: "3"}}). Token and cost sums cover only traces that
 * recorded typed usage/cost; {@code totalTokens} sums the trace-level total, which every trace
 * carries.
 *
 * @param key grouping dimension values for this group
 * @param runCount number of traces in the group
 * @param errorCount number of traces that failed
 * @param durationP50Millis median wall-clock duration in milliseconds
 * @param durationP95Millis 95th-percentile wall-clock duration in milliseconds
 * @param inputTokens summed uncached input tokens
 * @param outputTokens summed output tokens
 * @param cacheCreationTokens summed cache-write input tokens
 * @param cacheReadTokens summed cache-read input tokens
 * @param totalTokens summed trace-level token totals
 * @param costMicroUsd summed cost in micro-USD
 * @param thumbsUpCount summed positive feedback counts
 * @param thumbsDownCount summed negative feedback counts
 */
public record TraceRollup(
    Map<String, String> key,
    long runCount,
    long errorCount,
    long durationP50Millis,
    long durationP95Millis,
    long inputTokens,
    long outputTokens,
    long cacheCreationTokens,
    long cacheReadTokens,
    long totalTokens,
    long costMicroUsd,
    long thumbsUpCount,
    long thumbsDownCount) {

  public TraceRollup {
    key = Map.copyOf(key);
  }

  /** The fraction of runs in the group that completed without error, in {@code [0, 1]}. */
  public double successRate() {
    return runCount == 0 ? 0.0d : (double) (runCount - errorCount) / runCount;
  }
}
