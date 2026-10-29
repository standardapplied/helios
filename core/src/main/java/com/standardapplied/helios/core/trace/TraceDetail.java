/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.trace;

/**
 * How much content a session's trace stores.
 *
 * <p>{@link #STANDARD} stores no content: no prompt, answer, tool argument or tool result text,
 * only metadata such as model ids, token counts, costs, durations, tool names and outcomes.
 *
 * <p>{@link #VERBOSE} also stores the trace's {@code inputText} (the run's first user message), its
 * {@code outputText} (a successful run's result), and on each tool span {@code tool.arguments} (the
 * call's arguments as JSON) and {@code tool.result} (the result's output). Every content string is
 * redacted with the session's secret registry before it is stored.
 */
public enum TraceDetail {
  /** Metadata only: no prompt, answer, tool argument or tool result text. */
  STANDARD,

  /** Metadata plus the redacted input, output, tool arguments and tool results. */
  VERBOSE
}
