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
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * A reader that stalls never stalls the session: the loop and the turn's stream reader offer each
 * routine event to the reader's bounded {@link EventStream} without waiting, so a stream whose
 * buffer is full misses the event and the session runs on.
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
      var last = assertInstanceOf(QueryEvent.RunEnded.class, reader.events().getLast());
      assertEquals(terminal, last.result());
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
    var burst = new ArrayList<ModelChunk>();
    chunks(count).forEach(text -> burst.add(new ModelChunk.TextDelta(text)));
    burst.add(new ModelChunk.MessageStop(FinishReason.STOP.name(), Usage.of(1, 1), Map.of()));
    var stream =
        new CloseableIterator<ModelChunk>() {
          private int next;

          @Override
          public boolean hasNext() {
            if (next == 0) {
              beforeBurst.run();
            }
            if (next == count) {
              afterBurst.run();
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

  private static List<String> texts(EventCollector reader) {
    return reader.eventsOf(QueryEvent.AssistantText.class).stream()
        .map(QueryEvent.AssistantText::text)
        .toList();
  }
}
