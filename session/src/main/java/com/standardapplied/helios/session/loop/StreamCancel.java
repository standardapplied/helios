/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Cancels a model stream's {@link Flow.Subscription}, the provider's code, on a thread of its own
 * and waits up to {@code grace} for it, so a cancel that blocks never strands the thread that
 * cancels. A cancel still running when the grace passes, or when the waiting thread is interrupted,
 * is interrupted and logged as not known to have closed the stream, never taken for done; the
 * waiting thread's interrupt status is kept.
 */
final class StreamCancel {

  private static final Logger LOGGER = Logger.getLogger(TurnRunner.class.getName());

  private final String sessionId;
  private final Duration grace;

  StreamCancel(String sessionId, Duration grace) {
    this.sessionId = sessionId;
    this.grace = grace;
  }

  /**
   * The failure to report when {@code later} happened after {@code failure}: {@code failure}, with
   * {@code later} added to it as suppressed, unless {@code later} is an {@link Error} and {@code
   * failure} is not, so a host {@code Error} is never hidden behind an ordinary failure; then
   * {@code later}, carrying {@code failure}. Either may be {@code null}.
   */
  static Throwable fold(Throwable failure, Throwable later) {
    if (later == null || later == failure) {
      return failure;
    }
    if (failure == null) {
      return later;
    }
    var primary = later instanceof Error && !(failure instanceof Error) ? later : failure;
    primary.addSuppressed(primary == failure ? later : failure);
    return primary;
  }

  /**
   * Cancel {@code subscription} within the grace.
   *
   * @param failure what ended the stream, or {@code null}
   * @return {@code failure} folded with what the cancel threw ({@link #fold})
   */
  Throwable cancel(Flow.Subscription subscription, Throwable failure) {
    var cancelling = new FutureTask<Void>(subscription::cancel, null);
    var worker = Thread.ofVirtual().name("helios-stream-cancel-" + sessionId).start(cancelling);
    try {
      cancelling.get(grace.toNanos(), TimeUnit.NANOSECONDS);
      return failure;
    } catch (ExecutionException e) {
      return fold(failure, e.getCause());
    } catch (TimeoutException e) {
      worker.interrupt();
    } catch (InterruptedException e) {
      worker.interrupt();
      Thread.currentThread().interrupt();
    }
    LOGGER.warning(
        () ->
            "cancelling the model stream of session "
                + sessionId
                + " did not return within "
                + grace
                + "; the stream is not known to be closed");
    return failure;
  }
}
