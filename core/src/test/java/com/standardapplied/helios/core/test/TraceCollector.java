/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.test;

import ai.singlr.core.events.EventSink;
import ai.singlr.core.events.HeliosEvent;
import ai.singlr.core.trace.Trace;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test helper: an {@link EventSink} that captures the terminal {@link Trace} from every {@code
 * RunCompleted} or {@code RunFailed} it observes. Drop-in replacement for legacy {@code
 * CollectingTraceListener} in tests that just need to assert "what did the agent end up producing?"
 * without subscribing to the full event stream.
 */
public final class TraceCollector implements EventSink {

  private final List<Trace> traces = new CopyOnWriteArrayList<>();

  @Override
  public void onEvent(HeliosEvent event) {
    if (event instanceof HeliosEvent.RunCompleted rc) {
      traces.add(rc.trace());
    } else if (event instanceof HeliosEvent.RunFailed rf) {
      traces.add(rf.trace());
    }
  }

  /** Every terminal trace observed, in arrival order. Immutable snapshot. */
  public List<Trace> traces() {
    return List.copyOf(traces);
  }

  /** Count of terminal traces observed. */
  public int size() {
    return traces.size();
  }

  /** First (and usually only) trace. Throws if none. */
  public Trace first() {
    if (traces.isEmpty()) {
      throw new IllegalStateException("No traces captured yet");
    }
    return traces.get(0);
  }

  /** Most recent trace, or {@code null} when none observed yet. */
  public Trace latest() {
    return traces.isEmpty() ? null : traces.get(traces.size() - 1);
  }

  /** Drop captured traces. Useful between phases of a long test. */
  public void clear() {
    traces.clear();
  }

  /**
   * Convenience factory that returns an {@link EventSink} appending captured terminal {@link
   * Trace}s into the supplied list. Replaces {@code .withTraceListener(traces::add)} test idiom.
   */
  public static EventSink into(List<Trace> target) {
    return event -> {
      if (event instanceof HeliosEvent.RunCompleted rc) {
        target.add(rc.trace());
      } else if (event instanceof HeliosEvent.RunFailed rf) {
        target.add(rf.trace());
      }
    };
  }
}
