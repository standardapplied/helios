/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.trace;

import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.core.events.EventSink;
import com.standardapplied.helios.core.events.HeliosEvent;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tells a run's event sinks when a span opens and closes. Silent when there is no run id or no
 * sink; a sink that throws is logged and skipped so the others still hear the event.
 */
final class SpanEvents {

  private static final Logger LOG = Logger.getLogger(SpanBuilder.class.getName());

  private final List<EventSink> eventSinks;
  private final UUID runId;

  SpanEvents(List<EventSink> eventSinks, UUID runId) {
    this.eventSinks = eventSinks;
    this.runId = runId;
  }

  void opened(UUID spanId, UUID parentSpanId, String name) {
    if (silent()) {
      return;
    }
    fanOut(
        new HeliosEvent.SpanOpened(
            Ids.now().toInstant(),
            runId,
            Optional.empty(),
            spanId,
            Optional.ofNullable(parentSpanId),
            name));
  }

  void closed(Span span) {
    if (silent()) {
      return;
    }
    fanOut(
        new HeliosEvent.SpanClosed(
            Ids.now().toInstant(),
            runId,
            Optional.empty(),
            span.id(),
            span.duration() == null ? Duration.ZERO : span.duration(),
            span.success(),
            Optional.ofNullable(span.error())));
  }

  private boolean silent() {
    return eventSinks == null || eventSinks.isEmpty() || runId == null;
  }

  private void fanOut(HeliosEvent event) {
    for (var sink : eventSinks) {
      try {
        sink.onEvent(event);
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "EventSink threw on " + event.getClass().getSimpleName(), e);
      }
    }
  }
}
