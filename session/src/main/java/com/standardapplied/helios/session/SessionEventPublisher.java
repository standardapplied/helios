/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One session's event stream: live fan-out to the subscribers attached while the session runs, and
 * replay of the terminal {@link QueryEvent.LoopEnded} to a subscriber that attaches after it was
 * emitted. Every subscriber sees {@code LoopEnded} at most once, and exactly once if the session
 * emitted it: a subscriber is admitted either to the live stream, before the terminal event is
 * recorded, or to the replay, after — the two are decided under one lock that is never held while
 * an event is offered. {@link #subscribe} never throws for a non-null subscriber; a subscriber that
 * attaches after a session ended without a {@code LoopEnded} is completed at once.
 *
 * <p>Live delivery runs on a per-session virtual-thread executor through a {@link
 * SubmissionPublisher} with a 256-item buffer per subscriber. A subscriber that fills its buffer
 * does not pin the agent loop: {@link #emit} waits a bounded time and then drops the event for that
 * subscriber.
 */
final class SessionEventPublisher implements Flow.Publisher<QueryEvent> {

  private static final Logger LOGGER = Logger.getLogger(SessionEventPublisher.class.getName());
  private static final int SUBSCRIBER_BUFFER = 256;
  private static final long ROUTINE_EMIT_TIMEOUT_MS = 1_000L;
  private static final long CRITICAL_EMIT_TIMEOUT_MS = 30_000L;
  private static final long DRAIN_GRACE_SECONDS = 5;

  private final String sessionId;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final SubmissionPublisher<QueryEvent> live =
      new SubmissionPublisher<>(executor, SUBSCRIBER_BUFFER);
  private final Object admission = new Object();
  private QueryEvent.LoopEnded terminal;
  private boolean closed;

  SessionEventPublisher(String sessionId) {
    this.sessionId = sessionId;
  }

  @Override
  public void subscribe(Flow.Subscriber<? super QueryEvent> subscriber) {
    Objects.requireNonNull(subscriber, "subscriber must not be null");
    QueryEvent.LoopEnded replayed;
    synchronized (admission) {
      if (terminal == null && !closed) {
        live.subscribe(subscriber);
        return;
      }
      replayed = terminal;
    }
    var replay = new TerminalReplay(subscriber, replayed);
    subscriber.onSubscribe(replay);
    replay.subscribed();
  }

  /**
   * Offer {@code event} to every live subscriber, waiting a bounded time for one whose buffer is
   * full and then dropping the event for it. Critical control events (the terminal {@link
   * QueryEvent.LoopEnded}, {@link QueryEvent.QuestionAsked}, {@link QueryEvent.Error}) get the
   * longer wait and a WARNING when dropped; routine events are dropped with a FINE log so operators
   * can correlate gaps in a stream to a slow consumer.
   */
  void emit(QueryEvent event) {
    if (event instanceof QueryEvent.LoopEnded ended) {
      synchronized (admission) {
        terminal = ended;
      }
    }
    var critical = isCritical(event);
    var timeoutMs = critical ? CRITICAL_EMIT_TIMEOUT_MS : ROUTINE_EMIT_TIMEOUT_MS;
    var dropped = live.offer(event, timeoutMs, TimeUnit.MILLISECONDS, (sub, e) -> false);
    if (dropped < 0) {
      LOGGER.log(
          critical ? Level.WARNING : Level.FINE,
          () ->
              "dropped "
                  + event.getClass().getSimpleName()
                  + " for "
                  + Math.abs(dropped)
                  + " slow subscriber(s) on session "
                  + sessionId);
    }
  }

  /**
   * End the live stream and wait, for a bounded grace period, until every live subscriber has
   * drained its pending events and its {@code onComplete}. By the time this returns every
   * responsive subscriber has observed every event emitted to it, including the terminal one.
   */
  void close() {
    synchronized (admission) {
      closed = true;
    }
    live.close();
    executor.shutdown();
    try {
      if (!executor.awaitTermination(DRAIN_GRACE_SECONDS, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  ExecutorService executor() {
    return executor;
  }

  private static boolean isCritical(QueryEvent event) {
    return event instanceof QueryEvent.LoopEnded
        || event instanceof QueryEvent.QuestionAsked
        || event instanceof QueryEvent.Error;
  }

  /**
   * The subscription of a subscriber that attached after the session ended. It delivers the
   * terminal event once the subscriber has both returned from {@code onSubscribe} and requested,
   * then completes; with no terminal event to deliver it completes as soon as {@code onSubscribe}
   * returns. Signals are issued on the thread that satisfies the last condition, never
   * concurrently.
   */
  private static final class TerminalReplay implements Flow.Subscription {

    private final Flow.Subscriber<? super QueryEvent> subscriber;
    private final QueryEvent.LoopEnded terminal;
    private boolean subscribed;
    private boolean requested;
    private boolean finished;

    TerminalReplay(Flow.Subscriber<? super QueryEvent> subscriber, QueryEvent.LoopEnded terminal) {
      this.subscriber = subscriber;
      this.terminal = terminal;
      this.requested = terminal == null;
    }

    @Override
    public void request(long n) {
      if (n <= 0) {
        if (finish()) {
          subscriber.onError(
              new IllegalArgumentException("non-positive subscription request: " + n));
        }
        return;
      }
      synchronized (this) {
        requested = true;
      }
      deliver();
    }

    @Override
    public void cancel() {
      finish();
    }

    void subscribed() {
      synchronized (this) {
        subscribed = true;
      }
      deliver();
    }

    private void deliver() {
      synchronized (this) {
        if (!subscribed || !requested) {
          return;
        }
      }
      if (finish()) {
        if (terminal != null) {
          subscriber.onNext(terminal);
        }
        subscriber.onComplete();
      }
    }

    private synchronized boolean finish() {
      if (finished) {
        return false;
      }
      finished = true;
      return true;
    }
  }
}
