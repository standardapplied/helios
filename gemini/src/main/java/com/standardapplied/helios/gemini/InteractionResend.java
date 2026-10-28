/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.Exchange;
import com.standardapplied.helios.gemini.api.InteractionRequest;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.logging.Logger;

/**
 * Runs a turn's request, and resends it with the whole conversation when it continued an
 * interaction the server no longer holds: one that expired or was deleted, which the API answers
 * {@code 404} before any event streams. The resend replaces that request rather than retrying it:
 * the caller's stream becomes the resend's and sees nothing of the {@code 404}. A {@code 404}
 * answering the resend, or one answering a request that continued nothing, ends the stream as any
 * failure does. Closing closes the request in flight and sends no resend.
 */
final class InteractionResend implements Exchange<InteractionTurn> {

  /** Opens the stream answering one request, its response carrying the prompt stamp. */
  @FunctionalInterface
  interface Opener {

    /** The stream answering {@code request}, sent on its first {@code hasNext()}. */
    CloseableIterator<StreamEvent> open(InteractionRequest request, String promptStamp);
  }

  private static final Logger LOG = Logger.getLogger(InteractionResend.class.getName());

  private static final int NOT_FOUND = 404;

  private final Opener opener;

  /** Runs turns through {@code opener}. */
  InteractionResend(Opener opener) {
    this.opener = Objects.requireNonNull(opener, "opener must not be null");
  }

  @Override
  public CloseableIterator<StreamEvent> stream(InteractionTurn turn) {
    var first = opener.open(turn.request(), turn.promptStamp());
    return turn.whole() == null ? first : new Turn(turn, first);
  }

  private static boolean gone(StreamEvent event) {
    return event instanceof StreamEvent.Error error
        && error.cause() instanceof ProviderException refusal
        && refusal.statusCode() == NOT_FOUND;
  }

  /**
   * One continued turn's events, its first request's or its resend's. The lock guards the request
   * in flight, which {@link #close()} may close from another thread while the reader resends.
   */
  private final class Turn implements CloseableIterator<StreamEvent> {

    private final InteractionTurn turn;
    private final Object lock = new Object();
    private CloseableIterator<StreamEvent> current;
    private boolean closed;
    private boolean resendable = true;
    private StreamEvent next;

    Turn(InteractionTurn turn, CloseableIterator<StreamEvent> first) {
      this.turn = turn;
      this.current = first;
    }

    @Override
    public boolean hasNext() {
      while (next == null && current().hasNext()) {
        var event = current().next();
        if (resendable && gone(event)) {
          resend();
        } else {
          next = event;
        }
        resendable = false;
      }
      return next != null;
    }

    @Override
    public StreamEvent next() {
      if (!hasNext()) {
        throw new NoSuchElementException("the turn's stream has ended");
      }
      var event = next;
      next = null;
      return event;
    }

    @Override
    public void close() {
      CloseableIterator<StreamEvent> inFlight;
      synchronized (lock) {
        closed = true;
        inFlight = current;
      }
      inFlight.close();
    }

    private CloseableIterator<StreamEvent> current() {
      synchronized (lock) {
        return current;
      }
    }

    private void resend() {
      LOG.info(
          () ->
              "Interaction "
                  + turn.request().previousInteractionId()
                  + " is gone; resending the whole conversation");
      current().close();
      var opened = opener.open(turn.whole().get(), turn.promptStamp());
      synchronized (lock) {
        if (!closed) {
          current = opened;
          return;
        }
      }
      opened.close();
      throw new CancellationException("the turn's stream was closed before its resend");
    }
  }
}
