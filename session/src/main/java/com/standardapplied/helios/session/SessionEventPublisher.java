/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One session's event fan-out across its runs: every event {@link #emit}ted is offered, on the
 * emitting thread, to each {@link EventStream} opened before it and not yet ended. {@link #close}
 * ends every stream, and a stream opened after it ends at once; the lock that admits a stream is
 * never held while an event is offered.
 *
 * <p>A routine event never waits: a stream whose buffer is full misses it, and the number of
 * streams that missed it is logged at FINE. The critical control events ({@code RunEnded}, {@link
 * QueryEvent.QuestionAsked}, {@link QueryEvent.Error}) wait up to 30 s for room in each full
 * stream, and a drop is logged as a WARNING.
 */
final class SessionEventPublisher {

  private static final Logger LOGGER = Logger.getLogger(SessionEventPublisher.class.getName());
  private static final Duration CRITICAL_EMIT_TIMEOUT = Duration.ofSeconds(30);

  private final String sessionId;
  private final long criticalWaitNanos;
  private final Object admission = new Object();
  private final Set<BufferedEventStream> streams = new LinkedHashSet<>();
  private boolean closed;

  SessionEventPublisher(String sessionId) {
    this(sessionId, CRITICAL_EMIT_TIMEOUT);
  }

  /** For tests of the critical drop, which the production limit puts seconds away. */
  SessionEventPublisher(String sessionId, Duration criticalEmitTimeout) {
    this.sessionId = sessionId;
    this.criticalWaitNanos = criticalEmitTimeout.toNanos();
  }

  /** Open a stream that receives every event emitted from now on, or one already ended. */
  EventStream open() {
    var stream = new BufferedEventStream(this::detach);
    synchronized (admission) {
      if (!closed) {
        streams.add(stream);
        return stream;
      }
    }
    stream.end();
    return stream;
  }

  /** Offer {@code event} to every open stream, waiting for room only for a critical event. */
  void emit(QueryEvent event) {
    List<BufferedEventStream> targets;
    synchronized (admission) {
      targets = List.copyOf(streams);
    }
    var critical = isCritical(event);
    var waitNanos = critical ? criticalWaitNanos : 0;
    var missed = targets.stream().filter(stream -> !stream.offer(event, waitNanos)).count();
    if (missed > 0) {
      LOGGER.log(
          critical ? Level.WARNING : Level.FINE,
          () ->
              "dropped "
                  + event.getClass().getSimpleName()
                  + " for "
                  + missed
                  + " full event stream(s) on session "
                  + sessionId);
    }
  }

  /**
   * End every open stream: each reader drains what its stream buffered, then its iteration ends.
   */
  void close() {
    List<BufferedEventStream> ending;
    synchronized (admission) {
      closed = true;
      ending = List.copyOf(streams);
      streams.clear();
    }
    ending.forEach(BufferedEventStream::end);
  }

  /** The number of streams attached to the live fan-out, for tests. */
  int openStreams() {
    synchronized (admission) {
      return streams.size();
    }
  }

  private void detach(BufferedEventStream stream) {
    synchronized (admission) {
      streams.remove(stream);
    }
  }

  private static boolean isCritical(QueryEvent event) {
    return event instanceof QueryEvent.RunEnded
        || event instanceof QueryEvent.QuestionAsked
        || event instanceof QueryEvent.Error;
  }
}
