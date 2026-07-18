/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregated metrics for one group of traces produced by a rollup query. The {@code key} maps each
 * grouping dimension to its value for this group in dimension order (e.g., {@code {groupId:
 * "eval-batch-1"}} or {@code {promptName: "analytics", promptVersion: "3"}}). Token and cost sums
 * cover only traces that recorded typed usage/cost; {@code totalTokens} sums the trace-level total,
 * which every trace carries.
 *
 * @param key grouping dimension values for this group, in dimension order
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
    key = Collections.unmodifiableMap(new LinkedHashMap<>(key));
  }

  /** The fraction of runs in the group that completed without error, in {@code [0, 1]}. */
  public double successRate() {
    return runCount == 0 ? 0.0d : (double) (runCount - errorCount) / runCount;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Builder for TraceRollup. */
  public static class Builder {
    private Map<String, String> key = new LinkedHashMap<>();
    private long runCount;
    private long errorCount;
    private long durationP50Millis;
    private long durationP95Millis;
    private long inputTokens;
    private long outputTokens;
    private long cacheCreationTokens;
    private long cacheReadTokens;
    private long totalTokens;
    private long costMicroUsd;
    private long thumbsUpCount;
    private long thumbsDownCount;

    private Builder() {}

    public Builder withKey(Map<String, String> key) {
      this.key = new LinkedHashMap<>(key);
      return this;
    }

    public Builder withKeyValue(String dimension, String value) {
      this.key.put(dimension, value);
      return this;
    }

    public Builder withRunCount(long runCount) {
      this.runCount = runCount;
      return this;
    }

    public Builder withErrorCount(long errorCount) {
      this.errorCount = errorCount;
      return this;
    }

    public Builder withDurationP50Millis(long durationP50Millis) {
      this.durationP50Millis = durationP50Millis;
      return this;
    }

    public Builder withDurationP95Millis(long durationP95Millis) {
      this.durationP95Millis = durationP95Millis;
      return this;
    }

    public Builder withInputTokens(long inputTokens) {
      this.inputTokens = inputTokens;
      return this;
    }

    public Builder withOutputTokens(long outputTokens) {
      this.outputTokens = outputTokens;
      return this;
    }

    public Builder withCacheCreationTokens(long cacheCreationTokens) {
      this.cacheCreationTokens = cacheCreationTokens;
      return this;
    }

    public Builder withCacheReadTokens(long cacheReadTokens) {
      this.cacheReadTokens = cacheReadTokens;
      return this;
    }

    public Builder withTotalTokens(long totalTokens) {
      this.totalTokens = totalTokens;
      return this;
    }

    public Builder withCostMicroUsd(long costMicroUsd) {
      this.costMicroUsd = costMicroUsd;
      return this;
    }

    public Builder withThumbsUpCount(long thumbsUpCount) {
      this.thumbsUpCount = thumbsUpCount;
      return this;
    }

    public Builder withThumbsDownCount(long thumbsDownCount) {
      this.thumbsDownCount = thumbsDownCount;
      return this;
    }

    public TraceRollup build() {
      return new TraceRollup(
          key,
          runCount,
          errorCount,
          durationP50Millis,
          durationP95Millis,
          inputTokens,
          outputTokens,
          cacheCreationTokens,
          cacheReadTokens,
          totalTokens,
          costMicroUsd,
          thumbsUpCount,
          thumbsDownCount);
    }
  }
}
