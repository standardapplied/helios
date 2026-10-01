/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.events;

/**
 * Receives {@link HeliosEvent}s as they happen during a Helios primitive run.
 *
 * <p>Recommended surface for building live agentic UIs. Implementations must be fast and
 * non-blocking — these hooks fire on the agent loop's hot path. Use {@link EventSinkPolicy} to
 * shape backpressure when a sink can't keep up.
 *
 * <p>Exceptions thrown from {@link #onEvent} are caught by the dispatch layer and logged at {@code
 * WARNING} so other sinks still receive the event and the run continues. Sinks should not abort the
 * agent loop by throwing.
 */
@FunctionalInterface
public interface EventSink {

  /**
   * Called for every event in run order (per-{@code runId}).
   *
   * @param event the event; never {@code null}
   */
  void onEvent(HeliosEvent event);
}
