/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

/**
 * Anything that can hold spans — either a top-level {@link TraceBuilder} or a nested {@link
 * SpanBuilder}. Agent code threads this type through its loop so the same machinery produces
 * top-level spans (leader, standalone agent) or nested spans (worker running inside a delegation
 * tool call) depending on context.
 *
 * <p>Sealed because only these two concrete types can legitimately host spans: a new trace or an
 * in-flight span that accepts children. Future implementations would fragment the tracing model, so
 * we deliberately prevent them.
 */
public sealed interface SpanContainer permits TraceBuilder, SpanBuilder {

  /**
   * Create a new span inside this container. For {@link TraceBuilder} this is a top-level span in
   * the trace; for {@link SpanBuilder} it is a child of that span.
   *
   * @param name the span name
   * @param kind the span kind
   * @return the span builder
   */
  SpanBuilder span(String name, SpanKind kind);
}
