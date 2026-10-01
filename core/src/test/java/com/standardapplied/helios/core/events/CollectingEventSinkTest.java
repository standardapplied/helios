/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import ai.singlr.core.trace.Trace;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class CollectingEventSinkTest {

  private static final Instant NOW = Instant.parse("2026-05-13T10:00:00Z");

  @Test
  void emptyAfterConstruction() {
    var sink = new CollectingEventSink();
    assertEquals(0, sink.size());
    assertTrue(sink.events().isEmpty());
  }

  @Test
  void recordsEventsInOrder() {
    var sink = new CollectingEventSink();
    var runId = Ids.newId();
    var e1 = new HeliosEvent.IterationStarted(NOW, runId, Optional.empty(), 0, 10);
    var e2 = new HeliosEvent.IterationCompleted(NOW, runId, Optional.empty(), 0);

    sink.onEvent(e1);
    sink.onEvent(e2);

    assertEquals(2, sink.size());
    assertEquals(e1, sink.events().get(0));
    assertEquals(e2, sink.events().get(1));
  }

  @Test
  void eventsReturnsImmutableSnapshot() {
    var sink = new CollectingEventSink();
    sink.onEvent(new HeliosEvent.IterationStarted(NOW, Ids.newId(), Optional.empty(), 0, 10));

    var snapshot = sink.events();
    sink.onEvent(new HeliosEvent.IterationCompleted(NOW, Ids.newId(), Optional.empty(), 0));

    assertEquals(1, snapshot.size());
    assertEquals(2, sink.events().size());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.add(null));
  }

  @Test
  void eventsForFiltersByRunId() {
    var sink = new CollectingEventSink();
    var run1 = Ids.newId();
    var run2 = Ids.newId();
    sink.onEvent(new HeliosEvent.IterationStarted(NOW, run1, Optional.empty(), 0, 10));
    sink.onEvent(new HeliosEvent.IterationStarted(NOW, run2, Optional.empty(), 0, 10));
    sink.onEvent(new HeliosEvent.IterationCompleted(NOW, run1, Optional.empty(), 0));

    var run1Events = sink.eventsFor(run1);
    var run2Events = sink.eventsFor(run2);
    assertEquals(2, run1Events.size());
    assertEquals(1, run2Events.size());
    assertEquals(run1, run1Events.get(0).runId());
    assertEquals(run2, run2Events.get(0).runId());
  }

  @Test
  void eventsForRequiresNonNullRunId() {
    var sink = new CollectingEventSink();
    assertThrows(NullPointerException.class, () -> sink.eventsFor(null));
  }

  @Test
  void clearEmptiesEverything() {
    var sink = new CollectingEventSink();
    sink.onEvent(new HeliosEvent.IterationStarted(NOW, Ids.newId(), Optional.empty(), 0, 10));
    assertEquals(1, sink.size());

    sink.clear();
    assertEquals(0, sink.size());
  }

  @Test
  void onEventRejectsNull() {
    var sink = new CollectingEventSink();
    assertThrows(NullPointerException.class, () -> sink.onEvent(null));
  }

  @Test
  void concurrentEmittersDoNotLoseEvents() throws Exception {
    var sink = new CollectingEventSink();
    var threads = 32;
    var perThread = 100;
    var latch = new CountDownLatch(threads);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var t = 0; t < threads; t++) {
        pool.submit(
            () -> {
              try {
                var runId = Ids.newId();
                for (var i = 0; i < perThread; i++) {
                  sink.onEvent(
                      new HeliosEvent.IterationStarted(NOW, runId, Optional.empty(), i, 10000));
                }
              } finally {
                latch.countDown();
              }
            });
      }
      latch.await();
    }
    assertEquals(threads * perThread, sink.size());
  }

  @Test
  void worksWithDiverseEventTypes() {
    var sink = new CollectingEventSink();
    var runId = Ids.newId();
    sink.onEvent(new HeliosEvent.RunStarted(NOW, runId, Optional.empty(), "agent", Map.of()));
    sink.onEvent(
        new HeliosEvent.AssistantThinkingDelta(NOW, runId, Optional.empty(), "let me think"));
    sink.onEvent(new HeliosEvent.AssistantTextDelta(NOW, runId, Optional.empty(), "Hello"));
    sink.onEvent(
        new HeliosEvent.RunCompleted(
            NOW,
            runId,
            Optional.empty(),
            Trace.newBuilder().withDuration(Duration.ofMillis(1)).build()));

    assertEquals(4, sink.size());
  }
}
