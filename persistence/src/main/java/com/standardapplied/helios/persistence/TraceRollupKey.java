/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

/**
 * The grouping dimension for a trace rollup query. Runs with a null value for the dimension are
 * excluded from the rollup.
 */
public enum TraceRollupKey {
  /** Group by {@link Trace#name()}. */
  NAME,

  /** Group by {@link Trace#modelId()}. */
  MODEL_ID
}
