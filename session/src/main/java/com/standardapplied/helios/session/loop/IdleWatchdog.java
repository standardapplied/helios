/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Fails a model stream that stalls: re-armed on subscribe and on every chunk, it reports a {@link
 * TimeoutException} when no chunk arrives for {@code streamIdleTimeout}.
 *
 * <p>Two race-safety guards: after detaching the prior task, {@link #arm()} checks whether the
 * stream already ended before scheduling a fresh one, and the post-schedule {@code compareAndSet}
 * cancels the fresh task on the rare path where a concurrent arm beat it to the slot. Either guard
 * losing leaks neither a scheduler task nor a missed deadline.
 */
final class IdleWatchdog {

  private final ScheduledExecutorService scheduler;
  private final Duration idleTimeout;
  private final long idleTimeoutMillis;
  private final BooleanSupplier streamEnded;
  private final Consumer<TimeoutException> onIdle;
  private final AtomicReference<ScheduledFuture<?>> timer = new AtomicReference<>();

  IdleWatchdog(
      ScheduledExecutorService scheduler,
      Duration idleTimeout,
      BooleanSupplier streamEnded,
      Consumer<TimeoutException> onIdle) {
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout must not be null");
    this.idleTimeoutMillis = idleTimeout.toMillis();
    this.streamEnded = streamEnded;
    this.onIdle = onIdle;
  }

  /** Cancel any pending deadline and, unless the stream already ended, arm a fresh one. */
  void arm() {
    cancel();
    if (streamEnded.getAsBoolean()) {
      return;
    }
    var fresh = scheduler.schedule(this::fire, idleTimeoutMillis, TimeUnit.MILLISECONDS);
    if (!timer.compareAndSet(null, fresh)) {
      fresh.cancel(false);
    }
  }

  /** Cancel the pending deadline, if any. */
  void cancel() {
    var pending = timer.getAndSet(null);
    if (pending != null) {
      pending.cancel(false);
    }
  }

  private void fire() {
    onIdle.accept(
        new TimeoutException(
            "model stream emitted no chunk for "
                + idleTimeout
                + " (streamIdleTimeout); treating as stalled"));
  }
}
