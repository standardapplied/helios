/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.UserMessage;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One session of a {@link SessionTable}, its owner, the runs the runtime started on it and the
 * capacity it holds. It is <em>active</em> while a run started through {@link #send} has not
 * completed, and <em>idle</em> otherwise: from registration until its first run and from the end of
 * the last run in progress, so a run that still runs keeps it active after the close cancelled the
 * run queued behind it. Its charge is given back once: at once when it is evicted to make room,
 * else once its close returned and it is idle. Its mutable state is guarded by the table's lock.
 */
final class RegisteredSession {

  private static final Logger LOGGER = Logger.getLogger(SessionRegistry.class.getName());

  private final AgentSession session;
  private final Principal owner;
  private final Object lock;
  private final InstantSource clock;
  private final Runnable charge;
  private final CompletableFuture<ResultMessage> firstRun = new CompletableFuture<>();
  private CompletableFuture<ResultMessage> latest = firstRun;
  private int pendingRuns;
  private Instant idleSince;
  private boolean closed;
  private boolean released;

  /**
   * @param lock the table's lock, which guards this session's state
   * @param charge gives this session's capacity back; run under {@code lock}, at most once
   */
  RegisteredSession(
      AgentSession session, Principal owner, Object lock, InstantSource clock, Runnable charge) {
    this.session = session;
    this.owner = owner;
    this.lock = lock;
    this.clock = clock;
    this.charge = charge;
    this.idleSince = clock.instant();
  }

  AgentSession session() {
    return session;
  }

  boolean ownedBy(Principal principal) {
    return owner.equals(principal);
  }

  /**
   * Send {@code message} to the session and record the run it is part of, which keeps the session
   * active until it completes; the first run recorded also completes the first-run future. Sending
   * and recording happen under the table's lock, so runs are recorded in the order the session
   * started them, and no sweep sees a run started but not recorded.
   *
   * @return the future of the run the message is part of
   * @throws IllegalStateException if the session is closed or failed
   * @throws com.standardapplied.helios.session.SteeringQueueFullException if its queue is full
   */
  CompletableFuture<ResultMessage> send(UserMessage message) {
    synchronized (lock) {
      var run = session.send(message);
      if (latest != run) {
        if (latest == firstRun) {
          run.whenComplete(this::firstRunEnded);
        }
        latest = run;
        pendingRuns++;
        run.whenComplete((result, failure) -> ended());
      }
      return run;
    }
  }

  private void firstRunEnded(ResultMessage result, Throwable failure) {
    if (failure == null) {
      firstRun.complete(result);
    } else {
      firstRun.completeExceptionally(failure);
    }
  }

  /**
   * The run in progress, else the last run, else, for a session that never ran, a future that
   * completes with its first run.
   */
  CompletableFuture<ResultMessage> latest() {
    synchronized (lock) {
      return latest;
    }
  }

  /** Whether no run the runtime started is in progress; called under the table's lock. */
  boolean isIdle() {
    return pendingRuns == 0;
  }

  /** When the session last became idle; called under the table's lock. */
  Instant idleSince() {
    return idleSince;
  }

  /** Whether the session is idle for {@code retention} at {@code now}; under the table's lock. */
  boolean expired(Instant now, Duration retention) {
    return isIdle() && Duration.between(idleSince, now).compareTo(retention) >= 0;
  }

  /** Give the charge back now, unless it was; called under the table's lock. */
  void release() {
    if (!released) {
      released = true;
      charge.run();
    }
  }

  /**
   * Close the session, which the table unregistered, outside the table's lock; a failure to close
   * it is logged. Once the close returned, the charge is given back if the session is idle, else
   * when its run completes.
   */
  void close() {
    try {
      session.close();
    } catch (Throwable e) {
      LOGGER.log(
          Level.WARNING, "closing unregistered session " + session.sessionId() + " failed", e);
    } finally {
      synchronized (lock) {
        closed = true;
        if (isIdle()) {
          release();
        }
      }
    }
  }

  private void ended() {
    synchronized (lock) {
      if (--pendingRuns > 0) {
        return;
      }
      idleSince = clock.instant();
      if (closed) {
        release();
      }
    }
  }
}
