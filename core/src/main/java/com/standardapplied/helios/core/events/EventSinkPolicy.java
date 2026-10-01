/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.events;

/**
 * Backpressure policy for an {@link EventSink}.
 *
 * <p>Pluggable per-sink so different consumers on the same stream can pick different trade-offs.
 * The dispatch layer reads this policy when handing an event to the sink.
 */
public enum EventSinkPolicy {

  /**
   * Emitter blocks until the sink returns. Safe for in-process consumers that are reliably fast.
   * Default for new sinks.
   */
  BLOCK,

  /**
   * Buffered ring; oldest event is dropped if the buffer overflows. For UI consumers that prefer to
   * skip frames over stalling the agent loop.
   */
  DROP_OLDEST,

  /**
   * Emit every Nth event of the same kind. For ultra-high-rate text deltas where the UI just needs
   * "something updated" — never use for terminal events.
   */
  SAMPLE
}
