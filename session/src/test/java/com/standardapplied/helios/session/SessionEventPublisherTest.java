/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.BlockingModel;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.ask.AskUserQuestionOption;
import com.standardapplied.helios.session.ask.AskUserQuestionRequest;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.test.LogCapture;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.logging.LogRecord;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The fan-out offers each event to every open {@link EventStream} without waiting for a routine
 * one, waits a bounded time for room for a critical one, and replays the terminal {@code LoopEnded}
 * exactly once to a stream opened after it. The first group drives the fan-out directly, emitting
 * from a thread of its own as a session's loop does; the last group drives a real session, where
 * "after" is forced by opening a stream from inside another reader's handling of {@code LoopEnded}
 * or after the result has settled.
 */
final class SessionEventPublisherTest {

  private static final String SID = "sess-events";
  private static final Instant AT = Instant.parse("2026-10-02T00:00:00Z");
  private static final int CAPACITY = BufferedEventStream.CAPACITY;
  private static final QueryEvent TEXT = text(0);
  private static final QueryEvent QUESTION =
      new QueryEvent.QuestionAsked(
          SID,
          0,
          AT,
          new AskUserQuestionRequest(
              "q1",
              "Pick",
              "Which one?",
              List.of(
                  new AskUserQuestionOption("a", "first"),
                  new AskUserQuestionOption("b", "second")),
              false));
  private static final QueryEvent.LoopEnded ENDED =
      new QueryEvent.LoopEnded(
          SID,
          0,
          AT,
          new ResultMessage.Cancelled(
              SID, "done", Usage.of(0, 0), CostEstimate.zero(), Duration.ZERO));

  @Test
  void aStalledReaderDoesNotSlowTheEmitterAndKeepsTheEventsItHadRoomFor() {
    var publisher = new SessionEventPublisher(SID);
    var stream = publisher.open();
    var burst = IntStream.range(0, 2 * CAPACITY).mapToObj(SessionEventPublisherTest::text).toList();
    var logged = new CopyOnWriteArrayList<LogRecord>();

    try (var ignored = LogCapture.of(SessionEventPublisher.class, logged)) {
      emit(publisher, burst.toArray(QueryEvent[]::new));
    }
    publisher.close();

    assertEquals(burst.subList(0, CAPACITY), readToEnd(stream));
    assertEquals(CAPACITY, logged.size());
    assertEquals(
        "FINE dropped AssistantText for 1 full event stream(s) on session " + SID,
        describe(logged.getFirst()));
  }

  @Test
  void aCriticalEventWaitsForTheReaderToMakeRoom() {
    var publisher = new SessionEventPublisher(SID);
    var stream = publisher.open();
    emit(publisher, Collections.nCopies(CAPACITY, TEXT).toArray(QueryEvent[]::new));
    var events = stream.iterator();

    var emitting = started(() -> publisher.emit(QUESTION));
    assertSame(TEXT, events.next());
    Await.value("the question to find room", emitting);
    publisher.close();

    var rest = new ArrayList<QueryEvent>();
    events.forEachRemaining(rest::add);
    assertEquals(CAPACITY, rest.size());
    assertSame(QUESTION, rest.getLast());
  }

  @Test
  void aCriticalEventStillWithoutRoomAfterItsWaitIsDroppedWithAWarning() {
    var publisher = new SessionEventPublisher(SID, Duration.ofMillis(1));
    var stream = publisher.open();
    emit(publisher, Collections.nCopies(CAPACITY, TEXT).toArray(QueryEvent[]::new));
    var logged = new CopyOnWriteArrayList<LogRecord>();

    try (var ignored = LogCapture.of(SessionEventPublisher.class, logged)) {
      emit(
          publisher,
          new QueryEvent.Error(SID, 0, AT, SerializedError.of("Boom", "boom")),
          QUESTION,
          ENDED);
    }
    publisher.close();

    assertEquals(Collections.nCopies(CAPACITY, TEXT), readToEnd(stream));
    assertEquals(
        List.of(
            "WARNING dropped Error for 1 full event stream(s) on session " + SID,
            "WARNING dropped QuestionAsked for 1 full event stream(s) on session " + SID,
            "WARNING dropped LoopEnded for 1 full event stream(s) on session " + SID),
        logged.stream().map(SessionEventPublisherTest::describe).toList());
  }

  @Test
  void anInterruptEndsACriticalWaitDropsTheEventAndKeepsTheInterrupt() {
    var publisher = new SessionEventPublisher(SID);
    var stream = publisher.open();
    emit(publisher, Collections.nCopies(CAPACITY, TEXT).toArray(QueryEvent[]::new));

    var interrupted =
        call(
            () -> {
              Thread.currentThread().interrupt();
              publisher.emit(QUESTION);
              return Thread.currentThread().isInterrupted();
            });
    publisher.close();

    assertTrue(interrupted);
    assertEquals(Collections.nCopies(CAPACITY, TEXT), readToEnd(stream));
  }

  @Test
  void closingAStreamEndsACriticalWaitWithoutADrop() {
    var publisher = new SessionEventPublisher(SID);
    var stream = publisher.open();
    emit(publisher, Collections.nCopies(CAPACITY, TEXT).toArray(QueryEvent[]::new));
    var logged = new CopyOnWriteArrayList<LogRecord>();

    try (var ignored = LogCapture.of(SessionEventPublisher.class, logged)) {
      var emitting = started(() -> publisher.emit(QUESTION));
      stream.close();
      Await.value("the question's wait to end", emitting);
    }

    assertEquals(List.of(), logged);
    assertFalse(stream.iterator().hasNext());
  }

  @Test
  void anOfferToAClosedStreamIsNoMiss() {
    var stream = new BufferedEventStream(closed -> {});
    stream.close();

    assertTrue(stream.offer(TEXT, 0));
    assertFalse(stream.iterator().hasNext());
  }

  @Test
  void everyOpenStreamReceivesEveryEventInOrderAndEndsAfterDrainingWhenThePublisherCloses() {
    var publisher = new SessionEventPublisher(SID);
    var first = publisher.open();
    var second = publisher.open();

    emit(publisher, TEXT, QUESTION, ENDED);
    publisher.close();

    assertEquals(List.of(TEXT, QUESTION, ENDED), readToEnd(first));
    assertEquals(List.of(TEXT, QUESTION, ENDED), readToEnd(second));
  }

  @Test
  void anEmitWithNoOpenStreamLogsNothing() {
    var publisher = new SessionEventPublisher(SID);
    var logged = new CopyOnWriteArrayList<LogRecord>();

    try (var ignored = LogCapture.of(SessionEventPublisher.class, logged)) {
      emit(publisher, TEXT, ENDED);
    }

    assertEquals(List.of(), logged);
  }

  @Test
  void aStreamIsReadOnce() {
    var stream = new SessionEventPublisher(SID).open();
    stream.iterator();

    var second = assertThrows(IllegalStateException.class, stream::iterator);

    assertEquals("an EventStream is read once", second.getMessage());
  }

  @Test
  void nextPastTheEndThrowsAndRemoveIsUnsupported() {
    var publisher = new SessionEventPublisher(SID);
    var events = publisher.open().iterator();
    emit(publisher, TEXT);
    publisher.close();

    assertSame(TEXT, events.next());
    assertThrows(UnsupportedOperationException.class, events::remove);
    assertFalse(events.hasNext());
    assertThrows(NoSuchElementException.class, events::next);
  }

  @Test
  void closeFromAnotherThreadEndsABlockedRead() {
    var publisher = new SessionEventPublisher(SID);
    var stream = publisher.open();
    var reader = new FutureTask<>(() -> stream.iterator().hasNext());
    var thread = Thread.ofVirtual().start(reader);
    awaitBlocked(thread);

    stream.close();

    assertFalse(Await.value("the blocked read to end", reader));
    assertEquals(0, publisher.openStreams());
  }

  @Test
  void anInterruptEndsABlockedReadKeepsTheInterruptAndDetachesTheStream() {
    var publisher = new SessionEventPublisher(SID);
    var stream = publisher.open();
    var reader =
        new FutureTask<>(
            () -> List.of(stream.iterator().hasNext(), Thread.currentThread().isInterrupted()));
    var thread = Thread.ofVirtual().start(reader);
    awaitBlocked(thread);

    thread.interrupt();

    assertEquals(List.of(false, true), Await.value("the interrupted read to end", reader));
    assertEquals(0, publisher.openStreams());
  }

  @Test
  void anInterruptedReaderStopsWithEventsBufferedKeepsTheInterruptAndDetachesTheStream() {
    try (var session = (AgentSessionImpl) session()) {
      var stream = session.events();
      emit(session.lifecycle.events(), TEXT);

      var read =
          call(
              () -> {
                Thread.currentThread().interrupt();
                return List.of(stream.iterator().hasNext(), Thread.currentThread().isInterrupted());
              });

      assertEquals(List.of(false, true), read);
      assertEquals(0, session.lifecycle.events().openStreams());
    }
  }

  @Test
  void closeTwiceDoesNothingMore() {
    var publisher = new SessionEventPublisher(SID);
    var stream = publisher.open();
    var kept = publisher.open();
    emit(publisher, TEXT);

    stream.close();
    stream.close();

    assertEquals(1, publisher.openStreams());
    assertFalse(stream.iterator().hasNext());
    kept.close();
    assertEquals(0, publisher.openStreams());
  }

  @Test
  void aStreamOpenedAfterTheTerminalHoldsOnlyThatEventThenEnds() {
    var publisher = new SessionEventPublisher(SID);
    emit(publisher, TEXT, ENDED);
    var beforeClose = publisher.open();
    publisher.close();
    var afterClose = publisher.open();

    assertEquals(List.of(ENDED), readToEnd(beforeClose));
    assertEquals(List.of(ENDED), readToEnd(afterClose));
    assertEquals(0, publisher.openStreams());
  }

  @Test
  void aStreamOpenedAfterACloseWithoutATerminalEndsAtOnce() {
    var publisher = new SessionEventPublisher(SID);
    emit(publisher, TEXT);
    publisher.close();

    assertEquals(List.of(), readToEnd(publisher.open()));
  }

  @Test
  void aReaderThatKeepsUpReadsEveryEventInOrderAndEndsAfterLoopEnded() {
    try (var session = session()) {
      var read = new ArrayList<QueryEvent>();
      var stream = session.events();
      var reader =
          started(
              () -> {
                try (var events = stream) {
                  for (var event : events) {
                    read.add(event);
                  }
                }
              });
      session.send(UserMessage.text("hi"));
      var terminal = Await.value("the session to reach its terminal", session.result());
      Await.value("the reader to reach the end", reader);

      assertEquals(
          List.of(
              QueryEvent.UserMessageReceived.class,
              QueryEvent.AssistantText.class,
              QueryEvent.TurnEnded.class,
              QueryEvent.LoopEnded.class),
          read.stream().map(Object::getClass).toList());
      assertEquals(terminal, assertInstanceOf(QueryEvent.LoopEnded.class, read.getLast()).result());
    }
  }

  @Test
  void aStreamOpenedAfterTheResultSettledHoldsTheLoopEndedTheEarlyOneSaw() {
    try (var session = session()) {
      var early = new EventCollector().read(session.events());
      session.send(UserMessage.text("hi"));
      var terminal = Await.value("the session to reach its terminal", session.result());

      var replayed = assertInstanceOf(QueryEvent.LoopEnded.class, onlyEvent(session.events()));

      early.awaitDone();
      assertSame(early.events().getLast(), replayed);
      assertEquals(terminal, replayed.result());
    }
  }

  @Test
  void aStreamOpenedWhileLoopEndedIsBeingReadSeesItExactlyOnce() {
    try (var session = session()) {
      var late = new FutureTask<QueryEvent>(() -> onlyEvent(session.events()));
      var early =
          new EventCollector(
                  event -> {
                    if (event instanceof QueryEvent.LoopEnded) {
                      late.run();
                    }
                  })
              .read(session.events());
      session.send(UserMessage.text("hi"));
      Await.value("the session to reach its terminal", session.result());
      early.awaitDone();

      assertSame(early.events().getLast(), Await.value("the late stream", late));
    }
  }

  @Test
  void aStreamOpenedAfterAPreStartCloseEndsAtOnce() {
    var session = session();
    session.close();

    assertEquals(List.of(), readToEnd(session.events()));
  }

  @Test
  void aStreamOpenedAfterTheLoopEscapedWithAnErrorEndsAtOnce() {
    PreStopHook erroring =
        (response, ctx) -> {
          throw new AssertionError("simulated unrecoverable error");
        };
    try (var session =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(new OneReplyModel())
                .withSessionId(SID)
                .withHook(erroring)
                .build())) {
      session.send(UserMessage.text("hi"));
      assertInstanceOf(
          AssertionError.class,
          Await.failure("the result to settle exceptionally", session.result()));

      assertEquals(List.of(), readToEnd(session.events()));
    }
  }

  @Test
  void theTeardownEndsEveryOpenStreamBeforeTheResultSettles() {
    try (var session = (AgentSessionImpl) session()) {
      var stream = session.events();
      assertEquals(1, session.lifecycle.events().openStreams());

      session.send(UserMessage.text("hi"));
      var terminal = Await.value("the session to reach its terminal", session.result());

      assertEquals(0, session.lifecycle.events().openStreams());
      var events = readToEnd(stream);
      assertEquals(
          terminal, assertInstanceOf(QueryEvent.LoopEnded.class, events.getLast()).result());
    }
  }

  private static QueryEvent text(int index) {
    return new QueryEvent.AssistantText(SID, 0, AT, "t" + index);
  }

  private static String describe(LogRecord logRecord) {
    return logRecord.getLevel() + " " + logRecord.getMessage();
  }

  /** Emits from a thread of its own, as a session's loop does, and returns once it finished. */
  private static void emit(SessionEventPublisher publisher, QueryEvent... events) {
    call(
        () -> {
          for (var event : events) {
            publisher.emit(event);
          }
          return null;
        });
  }

  private static <T> T call(Callable<T> work) {
    return Await.value("the emitting thread to finish", started(work));
  }

  private static FutureTask<Void> started(Runnable work) {
    return started(
        () -> {
          work.run();
          return null;
        });
  }

  private static <T> FutureTask<T> started(Callable<T> work) {
    var task = new FutureTask<>(work);
    Thread.ofVirtual().start(task);
    return task;
  }

  private static void awaitBlocked(Thread thread) {
    Await.until("the reader to block", () -> thread.getState() == Thread.State.WAITING);
  }

  /** Reads a stream whose session has ended, on the calling thread, to its end. */
  private static List<QueryEvent> readToEnd(EventStream stream) {
    var events = new ArrayList<QueryEvent>();
    try (stream) {
      stream.forEach(events::add);
    }
    return events;
  }

  private static QueryEvent onlyEvent(EventStream stream) {
    var events = readToEnd(stream);
    assertEquals(1, events.size(), () -> "expected exactly one event, got " + events);
    return events.getFirst();
  }

  private static AgentSession session() {
    return AgentSession.create(
        SessionOptions.newBuilder().withModel(new OneReplyModel()).withSessionId(SID).build());
  }

  private static final class OneReplyModel implements BlockingModel {

    @Override
    public Response<Void> chat(List<Message> messages, List<Tool> tools) {
      return Response.newBuilder()
          .withContent("done")
          .withFinishReason(FinishReason.STOP)
          .withUsage(Usage.of(3, 2))
          .build();
    }

    @Override
    public String id() {
      return "test";
    }

    @Override
    public String provider() {
      return "test";
    }
  }
}
