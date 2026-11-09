/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.standardapplied.helios.core.test.Await;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * A stream counts the events its full buffer misses in a row and marks the gap with one {@link
 * QueryEvent.EventsDropped}, carrying the first missed event's common fields, just before the next
 * event it takes, or at its end; a closed stream marks nothing. Each test but the interrupted offer
 * offers and reads on the test thread, so the buffer's state at each step is the one the test
 * built; that one holds the stream's lock to close it between the interrupt and the offer's return.
 */
final class BufferedEventStreamTest {

  private static final int CAPACITY = BufferedEventStream.CAPACITY;
  private static final Instant AT = Instant.parse("2026-11-09T00:00:00Z");
  private static final Instant LATER = Instant.parse("2026-11-09T00:00:05Z");

  @Test
  void theMarkerOfAGapIsReadBetweenTheEventsBeforeItAndTheEventThatEndedIt() {
    var stream = filled();
    var events = stream.iterator();
    var missed = List.of(text("run-2", 3, LATER, "m0"), text("m1"), text("m2"));

    var misses = missed.stream().map(event -> stream.offer(event, 0)).toList();
    var first = events.next();
    var accepted = text("after");
    stream.offer(accepted, 0);
    stream.end();

    var read = new ArrayList<QueryEvent>(List.of(first));
    events.forEachRemaining(read::add);
    assertEquals(List.of(1L, 2L, 3L), misses);
    assertEquals(burst(), read.subList(0, CAPACITY));
    assertEquals(
        List.of(new QueryEvent.EventsDropped("sess", "run-2", 3, LATER, 3), accepted),
        read.subList(CAPACITY, read.size()));
  }

  @Test
  void theMarkerTakesTheBufferOnePastItsCapacityAndAFullerBufferStillMisses() {
    var stream = filled();
    var events = stream.iterator();
    stream.offer(text("missed"), 0);
    events.next();

    var accepted = stream.offer(text("accepted"), 0);
    var overflow = stream.offer(text("overflow"), 0);
    stream.end();
    var endedSize = stream.size();

    var read = new ArrayList<QueryEvent>();
    events.forEachRemaining(read::add);
    assertEquals(0, accepted);
    assertEquals(2, overflow);
    assertEquals(CAPACITY + 1, endedSize);
    assertEquals(burst().subList(1, CAPACITY), read.subList(0, CAPACITY - 1));
    assertEquals(
        List.of(
            new QueryEvent.EventsDropped("sess", "run-1", 0, AT, 1),
            text("accepted"),
            new QueryEvent.EventsDropped("sess", "run-1", 0, AT, 1)),
        read.subList(CAPACITY - 1, read.size()));
  }

  @Test
  void anOfferInterruptedAsTheStreamClosesCountsNothing() {
    var stream = filled();
    var offered = new CompletableFuture<Long>();
    var producer =
        Thread.ofPlatform()
            .start(() -> offered.complete(stream.offer(text("late"), Long.MAX_VALUE)));
    Await.until(
        "the offer to wait for room", () -> producer.getState() == Thread.State.TIMED_WAITING);

    stream.lock.lock();
    try {
      producer.interrupt();
      Await.until("the offer to wait for the lock", () -> stream.lock.hasQueuedThread(producer));
      stream.close();
    } finally {
      stream.lock.unlock();
    }

    assertEquals(0L, Await.value("the interrupted offer to return", offered));
  }

  @Test
  void eachGapIsMarkedWithACountOfItsOwn() {
    var stream = filled();
    var events = stream.iterator();
    stream.offer(text("a0"), 0);
    stream.offer(text("a1"), 0);
    events.next();
    stream.offer(text("between"), 0);
    var secondGap = stream.offer(text("b0"), 0);
    stream.end();

    var read = new ArrayList<QueryEvent>();
    events.forEachRemaining(read::add);
    assertEquals(3, secondGap);
    assertEquals(
        List.of(
            new QueryEvent.EventsDropped("sess", "run-1", 0, AT, 2),
            text("between"),
            new QueryEvent.EventsDropped("sess", "run-1", 0, AT, 1)),
        read.subList(CAPACITY - 1, read.size()));
  }

  @Test
  void aGapStillOpenWhenTheSessionEndsIsMarkedBeforeTheIterationEnds() {
    var stream = filled();
    stream.offer(text("run-1", 2, LATER, "m0"), 0);
    stream.offer(text("m1"), 0);

    stream.end();

    var read = new ArrayList<QueryEvent>();
    stream.forEach(read::add);
    assertEquals(burst(), read.subList(0, CAPACITY));
    assertEquals(
        List.of(new QueryEvent.EventsDropped("sess", "run-1", 2, LATER, 2)),
        read.subList(CAPACITY, read.size()));
  }

  @Test
  void aStreamThatMissedNothingEndsWithoutAMarker() {
    var stream = filled();

    stream.end();

    var read = new ArrayList<QueryEvent>();
    stream.forEach(read::add);
    assertEquals(burst(), read);
  }

  @Test
  void aStreamClosedWithAGapOpenMarksNothingWhenTheSessionEnds() {
    var stream = filled();
    stream.offer(text("missed"), 0);
    stream.close();

    stream.end();

    assertFalse(stream.iterator().hasNext());
  }

  private static BufferedEventStream filled() {
    var stream = new BufferedEventStream(closed -> {});
    burst().forEach(event -> assertEquals(0, stream.offer(event, 0)));
    return stream;
  }

  private static List<QueryEvent> burst() {
    return IntStream.range(0, CAPACITY).mapToObj(i -> text("t" + i)).toList();
  }

  private static QueryEvent text(String text) {
    return text("run-1", 0, AT, text);
  }

  private static QueryEvent text(String runId, long turnIndex, Instant at, String text) {
    return new QueryEvent.AssistantText("sess", runId, turnIndex, at, text);
  }
}
