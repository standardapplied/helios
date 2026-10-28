/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.gemini.api.InteractionRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The resend of a turn whose interaction is gone: only a {@code 404} answering a continued request
 * before any event is resent, once, and closing the turn stops it.
 */
class InteractionResendTest {

  private static final InteractionRequest CONTINUED =
      InteractionRequest.newBuilder().withModel("m").withPreviousInteractionId("int_1").build();

  private static final InteractionRequest WHOLE =
      InteractionRequest.newBuilder().withModel("m").build();

  private static final InteractionTurn TURN = new InteractionTurn(CONTINUED, "stamp", () -> WHOLE);

  private final List<InteractionRequest> opened = new ArrayList<>();

  @Test
  void aTurnThatContinuesNothingIsItsRequestsStream() {
    var stream = new Events(done());

    var turn =
        new InteractionResend((request, stamp) -> stream)
            .stream(new InteractionTurn(WHOLE, "stamp", null));

    assertSame(stream, turn);
  }

  @Test
  void aGoneInteractionIsResentUnderTheSameStamp() {
    var stamps = new ArrayList<String>();
    var resend = new Events(done());

    var events =
        drain(
            new InteractionResend(
                    (request, stamp) -> {
                      stamps.add(stamp);
                      return opened(request).size() == 1 ? new Events(failed(404)) : resend;
                    })
                .stream(TURN));

    assertEquals(List.of(CONTINUED, WHOLE), opened);
    assertEquals(List.of("stamp", "stamp"), stamps);
    assertEquals(1, events.size());
    assertTrue(events.getFirst() instanceof StreamEvent.Done);
  }

  @Test
  void a404AfterTheFirstEventIsNotResent() {
    var gone = failed(404);

    var events =
        drain(
            new InteractionResend(
                    (request, stamp) -> {
                      opened(request);
                      return new Events(new StreamEvent.KeepAlive(), gone);
                    })
                .stream(TURN));

    assertEquals(List.of(CONTINUED), opened);
    assertSame(gone, events.getLast());
  }

  @Test
  void anyOtherFailureOfTheContinuedRequestIsNotResent() {
    var unavailable = failed(503);

    var events =
        drain(
            new InteractionResend(
                    (request, stamp) -> {
                      opened(request);
                      return new Events(unavailable);
                    })
                .stream(TURN));

    assertEquals(List.of(CONTINUED), opened);
    assertEquals(List.of(unavailable), events);
  }

  @Test
  void aFailureToCommunicateIsNotResent() {
    var unreachable =
        new StreamEvent.Error(
            "Failed to communicate with Gemini API",
            new IOException("connection refused"),
            Response.newBuilder().withContent("").withFinishReason(FinishReason.ERROR).build());

    var events =
        drain(
            new InteractionResend(
                    (request, stamp) -> {
                      opened(request);
                      return new Events(unreachable);
                    })
                .stream(TURN));

    assertEquals(List.of(CONTINUED), opened);
    assertEquals(List.of(unreachable), events);
  }

  @Test
  void closingTheTurnWhileItResendsClosesTheResendAndEndsTheStream() {
    var turn = new AtomicReference<CloseableIterator<StreamEvent>>();
    var resend = new Events(done());
    turn.set(
        new InteractionResend(
                (request, stamp) -> {
                  if (opened(request).size() == 1) {
                    return new Events(failed(404));
                  }
                  turn.get().close();
                  return resend;
                })
            .stream(TURN));

    assertThrows(CancellationException.class, () -> turn.get().hasNext());
    assertTrue(resend.closed);
  }

  @Test
  void closingTheTurnClosesTheRequestInFlight() {
    var first = new Events(done());
    var turn = new InteractionResend((request, stamp) -> first).stream(TURN);

    turn.close();

    assertTrue(first.closed);
  }

  @Test
  void aTurnThatEndedHasNothingNext() {
    var turn = new InteractionResend((request, stamp) -> new Events(done())).stream(TURN);
    turn.next();

    assertFalse(turn.hasNext());
    assertThrows(NoSuchElementException.class, turn::next);
  }

  private List<InteractionRequest> opened(InteractionRequest request) {
    opened.add(request);
    return opened;
  }

  private static List<StreamEvent> drain(CloseableIterator<StreamEvent> turn) {
    var events = new ArrayList<StreamEvent>();
    turn.forEachRemaining(events::add);
    return events;
  }

  private static StreamEvent.Done done() {
    return new StreamEvent.Done(
        Response.newBuilder().withContent("ok").withFinishReason(FinishReason.STOP).build());
  }

  private static StreamEvent.Error failed(int status) {
    return new StreamEvent.Error(
        "API error (status " + status + ")",
        new GeminiException("API error (status " + status + ")", status),
        Response.newBuilder().withContent("").withFinishReason(FinishReason.ERROR).build());
  }

  /** A stream of fixed events that records being closed. */
  private static final class Events implements CloseableIterator<StreamEvent> {

    private final List<StreamEvent> events;
    private volatile boolean closed;

    Events(StreamEvent... events) {
      this.events = new ArrayList<>(List.of(events));
    }

    @Override
    public boolean hasNext() {
      return !events.isEmpty();
    }

    @Override
    public StreamEvent next() {
      return events.removeFirst();
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
