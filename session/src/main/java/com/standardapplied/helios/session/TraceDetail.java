/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.session;

/**
 * How much content a session's spans carry.
 *
 * <p>{@link #STANDARD} carries no content: no prompt, answer, tool argument, tool result or hook
 * text, only metadata such as model ids, token counts, costs, tool names and outcomes.
 *
 * <p>{@link #VERBOSE} also puts on the run's span {@code gen_ai.input.messages} (the run's first
 * user message) and {@code gen_ai.output.messages} (a successful run's result), on each tool span
 * {@code gen_ai.tool.call.arguments} (the call's arguments as JSON) and {@code
 * gen_ai.tool.call.result} (the result's output), and on each hook span {@code helios.hook.text}
 * (the reason or text its outcome carries). Every content string is redacted with the session's
 * secret registry before it reaches a span.
 */
public enum TraceDetail {
  /** Metadata only: no prompt, answer, tool argument, tool result or hook text. */
  STANDARD,

  /** Metadata plus the redacted input, output, tool arguments, tool results and hook texts. */
  VERBOSE
}
