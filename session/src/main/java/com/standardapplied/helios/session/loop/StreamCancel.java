/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import java.time.Duration;
import java.util.concurrent.Flow;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cancels a model stream's {@link Flow.Subscription}, the provider's code, on a thread of its own
 * and waits up to {@code grace} for it, so a cancel that blocks never strands the thread that
 * cancels. A cancel still running when the grace passes, or when the waiting thread is interrupted,
 * is interrupted and logged as not known to have closed the stream; the waiting thread's interrupt
 * status is kept. What the cancel throws is the session's: an {@link Error}, or one it carries, is
 * recorded by its {@link SessionEnding#failWith}, anything else is logged.
 */
final class StreamCancel {

  private static final Logger LOGGER = Logger.getLogger(TurnRunner.class.getName());

  private final String sessionId;
  private final Duration grace;
  private final SessionEnding ending;

  StreamCancel(String sessionId, Duration grace, SessionEnding ending) {
    this.sessionId = sessionId;
    this.grace = grace;
    this.ending = ending;
  }

  /** Cancel {@code subscription} within the grace. */
  void cancel(Flow.Subscription subscription) {
    var worker =
        Thread.ofVirtual()
            .name("helios-stream-cancel-" + sessionId)
            .start(
                () -> {
                  try {
                    subscription.cancel();
                  } catch (RuntimeException | Error e) {
                    if (!ending.failWith(e)) {
                      LOGGER.log(
                          Level.WARNING,
                          "cancelling the model stream of session " + sessionId + " threw",
                          e);
                    }
                  }
                });
    try {
      if (worker.join(grace)) {
        return;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    worker.interrupt();
    LOGGER.warning(
        () ->
            "cancelling the model stream of session "
                + sessionId
                + " did not return within "
                + grace
                + "; the stream is not known to be closed");
  }
}
