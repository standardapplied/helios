/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregated metrics for one group of runs produced by a rollup query. The {@code key} maps the
 * grouping dimension to its value for this group (e.g., {@code {name: "matchmaking"}} or {@code
 * {modelId: "gemini-3.8-flash"}}). Token and cost sums cover only runs that recorded usage and
 * cost; {@code totalTokens} sums the run-level total, which every run carries.
 *
 * @param key the grouping dimension and its value for this group
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
 * @param thumbsUpCount the group's run annotations with a positive rating, counted when read
 * @param thumbsDownCount the group's run annotations with a negative rating, counted when read
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

  /**
   * Creates a rollup, holding an unmodifiable copy of {@code key}.
   *
   * @throws NullPointerException if {@code key} is null
   */
  public TraceRollup {
    key = Collections.unmodifiableMap(new LinkedHashMap<>(key));
  }

  /**
   * Returns the fraction of runs in the group that completed without error.
   *
   * @return {@code (runCount - errorCount) / runCount}, in {@code [0, 1]} for a consistent rollup,
   *     or 0 for a group with no runs
   */
  public double successRate() {
    return runCount == 0 ? 0.0d : (double) (runCount - errorCount) / runCount;
  }

  /**
   * Returns an empty builder.
   *
   * @return a builder with an empty key and every count 0
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Builds a {@link TraceRollup}, used to reconstruct one from a rollup row. It validates nothing:
   * every value left unset is 0 and the key empty. Not thread-safe; use one builder per thread.
   */
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

    /**
     * Sets the grouping dimensions and their values, replacing any set before. The map is copied.
     *
     * @param key the dimension to value map; non-null
     * @return this builder
     */
    public Builder withKey(Map<String, String> key) {
      this.key = new LinkedHashMap<>(key);
      return this;
    }

    /**
     * Adds one grouping dimension and its value to the key, replacing that dimension's earlier
     * value.
     *
     * @param dimension the dimension name, such as {@code name} or {@code modelId}
     * @param value the group's value for the dimension
     * @return this builder
     */
    public Builder withKeyValue(String dimension, String value) {
      this.key.put(dimension, value);
      return this;
    }

    /**
     * Sets the number of runs in the group.
     *
     * @param runCount the run count
     * @return this builder
     */
    public Builder withRunCount(long runCount) {
      this.runCount = runCount;
      return this;
    }

    /**
     * Sets the number of runs in the group that failed.
     *
     * @param errorCount the failed run count
     * @return this builder
     */
    public Builder withErrorCount(long errorCount) {
      this.errorCount = errorCount;
      return this;
    }

    /**
     * Sets the median run duration in milliseconds.
     *
     * @param durationP50Millis the median
     * @return this builder
     */
    public Builder withDurationP50Millis(long durationP50Millis) {
      this.durationP50Millis = durationP50Millis;
      return this;
    }

    /**
     * Sets the 95th-percentile run duration in milliseconds.
     *
     * @param durationP95Millis the 95th percentile
     * @return this builder
     */
    public Builder withDurationP95Millis(long durationP95Millis) {
      this.durationP95Millis = durationP95Millis;
      return this;
    }

    /**
     * Sets the summed uncached input tokens of the runs that recorded usage.
     *
     * @param inputTokens the sum
     * @return this builder
     */
    public Builder withInputTokens(long inputTokens) {
      this.inputTokens = inputTokens;
      return this;
    }

    /**
     * Sets the summed output tokens of the runs that recorded usage.
     *
     * @param outputTokens the sum
     * @return this builder
     */
    public Builder withOutputTokens(long outputTokens) {
      this.outputTokens = outputTokens;
      return this;
    }

    /**
     * Sets the summed cache-write input tokens of the runs that recorded usage.
     *
     * @param cacheCreationTokens the sum
     * @return this builder
     */
    public Builder withCacheCreationTokens(long cacheCreationTokens) {
      this.cacheCreationTokens = cacheCreationTokens;
      return this;
    }

    /**
     * Sets the summed cache-read input tokens of the runs that recorded usage.
     *
     * @param cacheReadTokens the sum
     * @return this builder
     */
    public Builder withCacheReadTokens(long cacheReadTokens) {
      this.cacheReadTokens = cacheReadTokens;
      return this;
    }

    /**
     * Sets the summed run-level token totals of every run in the group.
     *
     * @param totalTokens the sum
     * @return this builder
     */
    public Builder withTotalTokens(long totalTokens) {
      this.totalTokens = totalTokens;
      return this;
    }

    /**
     * Sets the summed cost, in micro-USD, of the runs that recorded a cost.
     *
     * @param costMicroUsd the sum
     * @return this builder
     */
    public Builder withCostMicroUsd(long costMicroUsd) {
      this.costMicroUsd = costMicroUsd;
      return this;
    }

    /**
     * Sets the number of the group's run annotations with a positive rating.
     *
     * @param thumbsUpCount the count
     * @return this builder
     */
    public Builder withThumbsUpCount(long thumbsUpCount) {
      this.thumbsUpCount = thumbsUpCount;
      return this;
    }

    /**
     * Sets the number of the group's run annotations with a negative rating.
     *
     * @param thumbsDownCount the count
     * @return this builder
     */
    public Builder withThumbsDownCount(long thumbsDownCount) {
      this.thumbsDownCount = thumbsDownCount;
      return this;
    }

    /**
     * Builds the rollup.
     *
     * @return the rollup, holding an unmodifiable copy of the key
     */
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
