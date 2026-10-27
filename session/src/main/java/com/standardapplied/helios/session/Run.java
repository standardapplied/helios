/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.session.loop.SessionState;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One run of a session: its {@link SessionState}, on a token of its own that follows the session's
 * token from creation until {@link #unlink}, the future {@code send} returned for it, and its wall
 * clock, a virtual thread that waits on the run's token for {@code maxWallClock} and cancels the
 * run when it passes. Without it, {@code maxWallClock} would only be checked at turn boundaries, so
 * a turn blocked in a model stream, a tool, a permission prompt or a back-off would hold the run
 * indefinitely; the cancellation cuts the turn and the classifier reports {@link
 * ResultMessage.ErrorMaxWallClock}. Every end of a run cancels its token, so the wait always ends.
 */
final class Run {

  private static final InheritableThreadLocal<SessionState> CURRENT =
      new InheritableThreadLocal<>();

  private final CancellationToken sessionToken;
  private final SessionState state;
  private final CompletableFuture<ResultMessage> result;
  private final CancellationToken.Registration link;
  private final Duration maxWallClock;
  private final Thread thread;

  /**
   * The next run of {@code session}, its token cancelled with the session's reason as soon as the
   * session is cancelled, at once if it already is. {@link #start} starts the run's own virtual
   * thread, which binds itself, and every thread it starts, to this run, starts the wall clock on a
   * virtual thread of its own, then runs {@code body}.
   */
  Run(
      SessionState session,
      CompletableFuture<ResultMessage> result,
      Duration maxWallClock,
      Consumer<Run> body) {
    this.sessionToken = session.cancellation();
    this.state = new SessionState(session, new CancellationToken());
    this.result = result;
    this.maxWallClock = maxWallClock;
    this.link = sessionToken.onCancel(this::followSession);
    this.thread =
        Thread.ofVirtual()
            .name("helios-agent-run-" + session.sessionId())
            .unstarted(
                () -> {
                  CURRENT.set(state);
                  BoundedTask.start(
                      "helios-wall-clock-" + state.sessionId(),
                      this::awaitWallClock,
                      state.ending()::failWith);
                  body.accept(this);
                });
  }

  SessionState state() {
    return state;
  }

  CompletableFuture<ResultMessage> result() {
    return result;
  }

  void start() {
    thread.start();
  }

  /**
   * Wait until the run's thread has ended, by the {@link System#nanoTime()} instant {@code
   * deadlineNanos}. Its thread settles {@link #result} last, so the future is settled once the
   * thread ended; a wait on the future itself may run a caller's callback of it on the waiting
   * thread. An interrupt ends the wait, its status kept.
   */
  void awaitEnd(long deadlineNanos) {
    try {
      thread.join(Duration.ofNanos(deadlineNanos - System.nanoTime()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * The state of the run whose thread started the calling thread, directly or not, so work that
   * outlives its run never acts for a later one.
   *
   * @throws IllegalStateException if no run started the calling thread
   */
  static SessionState stateOfThisThread() {
    return Optional.ofNullable(CURRENT.get())
        .orElseThrow(() -> new IllegalStateException("no run started this thread"));
  }

  /**
   * Whether this run started the calling thread, directly or not. A thread an earlier run started,
   * such as a pooled thread a tool's work created, is not this run's.
   */
  boolean isThreadOfThisRun() {
    return CURRENT.get() == state;
  }

  /**
   * Cancel the run with the reason {@code "interrupted"}. The calling thread counts as the run's
   * while it does, so the token's callbacks, which run on threads it starts, count as the run's
   * too, and one that closes the session does not wait for the run it is part of.
   */
  void interrupt() {
    var previous = CURRENT.get();
    CURRENT.set(state);
    try {
      state.cancel("interrupted");
    } finally {
      CURRENT.set(previous);
    }
  }

  /**
   * Cancel the run with the reason of the session's cancelled token, as the link does once its
   * callback runs; a close calls it so the run is cancelled before the close returns.
   */
  void followSession() {
    state.cancel(sessionToken.reason().orElseThrow());
  }

  /** Stop following the session's token, once the run has ended. */
  void unlink() {
    link.remove();
  }

  /** What the wall clock does at its deadline, package-private so a test can run it at once. */
  void expireWallClock() {
    state.cancel("maxWallClock exceeded after " + maxWallClock.toMillis() + "ms");
  }

  private Void awaitWallClock() throws InterruptedException {
    var ended = new CountDownLatch(1);
    var registration = state.cancellation().onCancel(ended::countDown);
    try {
      if (!ended.await(maxWallClock.toNanos(), TimeUnit.NANOSECONDS)) {
        expireWallClock();
      }
    } finally {
      registration.remove();
    }
    return null;
  }
}
