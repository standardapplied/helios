/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

/**
 * The grouping dimension for a trace rollup query. Traces with a null value for the dimension are
 * excluded from the rollup — a trace without a {@code groupId} belongs to no eval group.
 */
public enum TraceRollupKey {
  /** Group by {@link Trace#groupId()}. */
  GROUP_ID,

  /** Group by {@link Trace#promptName()} + {@link Trace#promptVersion()}. */
  PROMPT,

  /** Group by {@link Trace#name()}. */
  NAME,

  /** Group by {@link Trace#modelId()}. */
  MODEL_ID
}
