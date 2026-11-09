/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * A reader that stalls never stalls the session: the loop and the turn's stream reader offer each
 * routine event to the reader's bounded {@link EventStream} without waiting, so a stream whose
 * buffer is full misses the event and the session runs on. The reader learns where: one {@link
 * QueryEvent.EventsDropped} stands for each run of missed events.
 *
 * <p>The tests drive a real session whose model streams a burst of text chunks to the turn's
 * reader. A reader that stalls on its first event holds that event and lets its buffer fill, so the
 * next event can only be missed; a reader that keeps up receives a burst that fits the buffer
 * whole.
 */
final class SafeEmitBackpressureTest {

  private static final int CAPACITY = BufferedEventStream.CAPACITY;

  @Test
  void aStalledReaderMissesTheEventItHasNoRoomForAndTheTurnKeepsItsContent() {
    var stalled = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var backlogDelivered = new CountDownLatch(1 + CAPACITY);
    var overflowEmitted = new CountDownLatch(1);
    var reader =
        new EventCollector(
            event -> {
              if (event instanceof QueryEvent.UserMessageReceived) {
                stalled.countDown();
                block(release);
              }
              backlogDelivered.countDown();
            });
    var model =
        burstModel(
            CAPACITY + 1,
            () -> block(stalled),
            () -> {
              overflowEmitted.countDown();
              block(backlogDelivered);
            });

    try (var session = session("sess-backpressure-slow", model)) {
      reader.read(session.events());
      var future = session.send(UserMessage.text("go"));

      Await.latch("the turn to emit past the stalled reader's full buffer", overflowEmitted);
      release.countDown();
      var terminal = Await.value("the session to finish", future);
      awaitCompletion(reader);

      var success = assertInstanceOf(ResultMessage.Success.class, terminal);
      assertEquals(
          String.join("", chunks(CAPACITY + 1)),
          success.result(),
          "the dropped event must not cost the turn its content");
      assertEquals(
          chunks(CAPACITY),
          texts(reader),
          "exactly the one event offered to the full buffer is missed");
      assertEquals(
          List.of(1L),
          reader.eventsOf(QueryEvent.EventsDropped.class).stream()
              .map(QueryEvent.EventsDropped::count)
              .toList(),
          "the missed event is marked where it would have been");
      var last = assertInstanceOf(QueryEvent.RunEnded.class, reader.events().getLast());
      assertEquals(terminal, last.result());
    }
  }

  /**
   * The reproduction from Light Grid's report: a reader held three times misses three runs of a
   * 2,000-word stream. The reader holds at its first event and, after each drain, at the last word
   * its buffer took; the model releases the reader before chunks 600, 1300 and 2000 and waits for
   * it to reach its next hold, so the buffer's state at every chunk is the one the test built.
   */
  @Test
  void aHeldReaderReadsEveryWordItWasGivenInOrderAndOneMarkerCountingEachGap() {
    var words = 2000;
    var holds = IntStream.range(0, 4).mapToObj(i -> new Hold()).toList();
    holds.getLast().release();
    var holdAfter = Map.of("c254 ", holds.get(1), "c854 ", holds.get(2), "c1554 ", holds.get(3));
    var reader =
        new EventCollector(
            event -> {
              switch (event) {
                case QueryEvent.RunStarted ignored -> holds.getFirst().hold();
                case QueryEvent.AssistantText text when holdAfter.containsKey(text.text()) ->
                    holdAfter.get(text.text()).hold();
                default -> {}
              }
            });
    var releases = List.of(0, 600, 1300, words);
    var model =
        burstModel(
            words,
            index -> {
              var hold = releases.indexOf(index);
              if (hold > 0) {
                holds.get(hold - 1).release();
              }
              if (hold >= 0) {
                holds.get(hold).awaitHeld();
              }
            });

    try (var session = session("sess-backpressure-gaps", model)) {
      reader.read(session.events());
      var terminal = Await.value("the session to finish", session.send(UserMessage.text("go")));
      awaitCompletion(reader);

      var success = assertInstanceOf(ResultMessage.Success.class, terminal);
      assertEquals(String.join("", chunks(words)), success.result());
      var markers = reader.eventsOf(QueryEvent.EventsDropped.class);
      assertEquals(
          List.of(345L, 445L, 445L),
          markers.stream().map(QueryEvent.EventsDropped::count).toList());
      assertEquals(
          List.of(terminal.runId()),
          markers.stream().map(QueryEvent.EventsDropped::runId).distinct().toList());
      assertEquals(words, wordsAccountedFor(reader.events()));
    }
  }

  @Test
  void aReaderThatKeepsUpMissesNothing() {
    var burst = 100;
    var reader = new EventCollector();

    try (var session = session("sess-backpressure-fast", burstModel(burst, () -> {}, () -> {}))) {
      reader.read(session.events());
      var future = session.send(UserMessage.text("go"));

      assertInstanceOf(ResultMessage.Success.class, Await.value("the session to finish", future));
      awaitCompletion(reader);

      assertEquals(chunks(burst), texts(reader), "a reader that keeps up misses nothing");
    }
  }

  private static AgentSession session(String sessionId, Model model) {
    return AgentSession.open(
        SessionOptions.newBuilder().withModel(model).withSessionId(sessionId).build());
  }

  private static List<String> chunks(int count) {
    return IntStream.range(0, count).mapToObj(i -> "c" + i + " ").toList();
  }

  private static void block(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while blocked on a test latch", e);
    }
  }

  /**
   * Model whose stream yields {@code count} text chunks to the turn's reader, running {@code
   * beforeBurst} ahead of the first chunk and {@code afterBurst} behind the last.
   */
  private static Model burstModel(int count, Runnable beforeBurst, Runnable afterBurst) {
    return burstModel(
        count,
        index -> {
          if (index == 0) {
            beforeBurst.run();
          }
          if (index == count) {
            afterBurst.run();
          }
        });
  }

  /**
   * Model whose stream yields {@code count} text chunks, then its stop, to the turn's reader,
   * running {@code beforeChunk} with each one's index before it; the stop's index is {@code count}.
   */
  private static Model burstModel(int count, IntConsumer beforeChunk) {
    var burst = new ArrayList<ModelChunk>();
    chunks(count).forEach(text -> burst.add(new ModelChunk.TextDelta(text)));
    burst.add(new ModelChunk.MessageStop(FinishReason.STOP.name(), Usage.of(1, 1), Map.of()));
    var stream =
        new CloseableIterator<ModelChunk>() {
          private int next;

          @Override
          public boolean hasNext() {
            if (next < burst.size()) {
              beforeChunk.accept(next);
            }
            return next < burst.size();
          }

          @Override
          public ModelChunk next() {
            return burst.get(next++);
          }

          @Override
          public void close() {}
        };
    return ScriptedModel.newBuilder().withStreamTurn(stream).build();
  }

  private static void awaitCompletion(EventCollector reader) {
    reader.awaitRunEnded();
    assertNull(reader.error().orElse(null), "the reader must end without an error");
  }

  /**
   * Walks {@code events} as a reader assembling the text would: each word is the next one expected,
   * and each marker skips the words it counts. Returns how many words the walk accounted for.
   */
  private static long wordsAccountedFor(List<QueryEvent> events) {
    var expected = 0L;
    for (var event : events) {
      switch (event) {
        case QueryEvent.AssistantText text -> assertEquals("c" + expected++ + " ", text.text());
        case QueryEvent.EventsDropped dropped -> expected += dropped.count();
        default -> {}
      }
    }
    return expected;
  }

  private static List<String> texts(EventCollector reader) {
    return reader.eventsOf(QueryEvent.AssistantText.class).stream()
        .map(QueryEvent.AssistantText::text)
        .toList();
  }

  /** A point the reader stops at, holding the event it read, until the model releases it. */
  private static final class Hold {

    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    void hold() {
      held.countDown();
      block(released);
    }

    void awaitHeld() {
      Await.latch("the reader to drain its buffer to the hold", held);
    }

    void release() {
      released.countDown();
    }
  }
}
