/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

/** Classifies the type of work a span represents. */
public enum SpanKind {
  AGENT,
  MODEL_CALL,
  TOOL_EXECUTION,
  MEMORY_OP,
  WORKFLOW,
  CUSTOM
}
