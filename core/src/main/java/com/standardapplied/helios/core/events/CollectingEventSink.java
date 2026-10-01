/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.events;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Thread-safe {@link EventSink} that accumulates every event into a list.
 *
 * <p>Use cases:
 *
 * <ul>
 *   <li>Tests — assert that the expected sequence of events fired.
 *   <li>Snapshot UIs — render the full history when ready, rather than streaming.
 *   <li>Post-hoc audit — examine the trajectory after a run completes.
 * </ul>
 *
 * <p>{@link #events()} returns an immutable snapshot; subsequent events are not reflected in the
 * returned list. {@link #eventsFor(UUID)} filters by run id when multiplexing many concurrent runs
 * through the same sink.
 */
public final class CollectingEventSink implements EventSink {

  private final List<HeliosEvent> events = new CopyOnWriteArrayList<>();

  @Override
  public void onEvent(HeliosEvent event) {
    Objects.requireNonNull(event, "event");
    events.add(event);
  }

  /** Immutable snapshot of every event received so far, in arrival order. */
  public List<HeliosEvent> events() {
    return List.copyOf(events);
  }

  /** Immutable snapshot filtered to a single run id, preserving arrival order. */
  public List<HeliosEvent> eventsFor(UUID runId) {
    Objects.requireNonNull(runId, "runId");
    return events.stream()
        .filter(e -> e.runId().equals(runId))
        .collect(Collectors.toUnmodifiableList());
  }

  /** Drop everything. Useful between test cases that share a sink instance. */
  public void clear() {
    events.clear();
  }

  /** Number of events received so far. */
  public int size() {
    return events.size();
  }
}
