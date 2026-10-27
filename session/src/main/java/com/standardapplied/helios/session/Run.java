/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.session.loop.SessionState;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

  private final SessionState state;
  private final CompletableFuture<ResultMessage> result;
  private final CancellationToken.Registration link;
  private final Duration maxWallClock;

  /**
   * The next run of {@code session}, its token cancelled with the session's reason as soon as the
   * session is cancelled, at once if it already is.
   */
  Run(SessionState session, CompletableFuture<ResultMessage> result, Duration maxWallClock) {
    var sessionToken = session.cancellation();
    this.state = new SessionState(session, new CancellationToken());
    this.result = result;
    this.maxWallClock = maxWallClock;
    this.link = sessionToken.onCancel(() -> state.cancel(sessionToken.reason().orElseThrow()));
  }

  SessionState state() {
    return state;
  }

  CompletableFuture<ResultMessage> result() {
    return result;
  }

  /** Start the wall clock on a virtual thread of its own. */
  void startWallClock() {
    BoundedTask.start(
        "helios-wall-clock-" + state.sessionId(), this::awaitWallClock, state.ending()::failWith);
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
