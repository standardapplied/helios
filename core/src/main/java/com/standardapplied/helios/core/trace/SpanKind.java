/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.trace;

/** Classifies the type of work a span represents. */
public enum SpanKind {
  AGENT,
  MODEL_CALL,
  TOOL_EXECUTION,
  MEMORY_OP,
  WORKFLOW,
  CUSTOM
}
