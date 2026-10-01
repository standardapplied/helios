/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

/**
 * Controls the verbosity of span attributes captured during tracing.
 *
 * <p>{@link #STANDARD} captures lightweight metadata: model id, token counts, tool name, tool call
 * id, and finish reason. These are always small strings.
 *
 * <p>{@link #VERBOSE} additionally captures potentially large text payloads: tool call arguments,
 * tool execution results, and model thinking/reasoning content. Use for debugging and evaluation.
 */
public enum TraceDetail {
  /** Lightweight span attributes only (model, tokens, tool name, finish reason). */
  STANDARD,

  /** All attributes including tool arguments, tool results, and thinking content. */
  VERBOSE
}
