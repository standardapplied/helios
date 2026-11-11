/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.model.CancellationToken;
import com.standardapplied.helios.session.loop.SessionState;
import io.opentelemetry.context.Context;
import java.time.Duration;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One run of a session: its {@link SessionState}, on a token of its own that follows the session's
 * token from creation until {@link #unlink}, the future {@code send} returned for it, the
 * OpenTelemetry {@link Context} of the {@code send} that started it, its span's parent, and its
 * wall clock, a virtual thread that waits on the run's token for {@code maxWallClock} and cancels
 * the run when it passes. Without it, {@code maxWallClock} would only be checked at turn
 * boundaries, so a turn blocked in a model stream, a tool, a permission prompt or a back-off would
 * hold the run indefinitely; the cancellation cuts the turn and the classifier reports {@link
 * ResultMessage.ErrorMaxWallClock}. Every end of a run cancels its token, so the wait always ends.
 */
final class Run {

  private static final InheritableThreadLocal<SessionState> CURRENT =
      new InheritableThreadLocal<>();

  private final CancellationToken sessionToken;
  private final SessionState state;
  private final CompletableFuture<ResultMessage> result;
  private final Context parent;
  private final CancellationToken.Registration link;
  private final Duration maxWallClock;
  private final Thread thread;
  private final Queue<Thread> settlers = new ConcurrentLinkedQueue<>();

  /**
   * The next run of {@code session}, its token cancelled with the session's reason as soon as the
   * session is cancelled, at once if it already is. {@link #start} starts the run's own virtual
   * thread, which binds itself, and every thread it starts, to this run, then runs {@code body},
   * which starts the wall clock ({@link #startWallClock}).
   */
  Run(
      SessionState session,
      CompletableFuture<ResultMessage> result,
      Context parent,
      Duration maxWallClock,
      Consumer<Run> body) {
    this.sessionToken = session.cancellation();
    this.state = new SessionState(session, new CancellationToken());
    this.result = result;
    this.parent = parent;
    this.maxWallClock = maxWallClock;
    this.link = sessionToken.onCancel(this::followSession);
    this.thread =
        Thread.ofVirtual()
            .name("helios-agent-run-" + session.sessionId())
            .unstarted(
                () -> {
                  CURRENT.set(state);
                  body.accept(this);
                });
  }

  /**
   * Start the wall clock on a virtual thread of its own, in the context current on the run's
   * thread; called by the run's body.
   */
  void startWallClock() {
    BoundedTask.start(
        "helios-wall-clock-" + state.sessionId(), this::awaitWallClock, state.ending()::failWith);
  }

  SessionState state() {
    return state;
  }

  CompletableFuture<ResultMessage> result() {
    return result;
  }

  /** The context current on the thread whose {@code send} started the run. */
  Context parent() {
    return parent;
  }

  void start() {
    thread.start();
  }

  /**
   * Wait until the run's thread has ended, then each thread that settles the future of a next run
   * kept from starting but the calling one, as a callback of that future that closes the session,
   * by the {@link System#nanoTime()} instant {@code deadlineNanos}. Each thread settles its future
   * last, so every future is settled once the threads ended; a wait on a future itself may run a
   * caller's callback of it on the waiting thread. An interrupt ends the wait, its status kept.
   */
  void awaitEnd(long deadlineNanos) {
    try {
      if (thread.join(Duration.ofNanos(deadlineNanos - System.nanoTime()))) {
        for (var settler : settlers) {
          if (settler != Thread.currentThread()) {
            settler.join(Duration.ofNanos(deadlineNanos - System.nanoTime()));
          }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Settle the future of a next run kept from starting, by this run's end or by an interrupt while
   * this run ended, with {@code settle}, on a virtual thread of its own, so a callback of either
   * future may wait for the other; called before this run's thread ends, so {@link #awaitEnd} waits
   * for it too.
   */
  void settlePrevented(Runnable settle) {
    settlers.add(
        Thread.ofVirtual().name("helios-prevented-run-" + state.sessionId()).start(settle));
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
    state.expireWallClock();
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
