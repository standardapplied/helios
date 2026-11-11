/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The idle timeout of one stream's waits for a line. The consumer marks each wait with {@link
 * #begin()} and ends it with {@link #end(long)}; a watchdog virtual thread, {@code
 * helios-sse-idle}, started by the first wait, claims a wait still open once the timeout has passed
 * and runs {@code onIdle}. Exactly one side settles a wait: a wait the watchdog claimed fails, one
 * the consumer ended first cannot be claimed. {@link #stop()} ends the watchdog, and no watchdog
 * starts after it.
 */
final class IdleWatch {

  /** The monotonic time and the sleep the watch paces itself by. */
  interface Pace {

    /** {@link System#nanoTime()} and {@link Thread#sleep(Duration)}. */
    Pace SYSTEM =
        new Pace() {
          @Override
          public long nanoTime() {
            return System.nanoTime();
          }

          @Override
          public void sleep(long nanos) throws InterruptedException {
            Thread.sleep(Duration.ofNanos(nanos));
          }
        };

    /** The current monotonic time in nanoseconds, as {@link System#nanoTime()} counts it. */
    long nanoTime();

    /** Sleeps {@code nanos}, returning at once when it is not positive. */
    void sleep(long nanos) throws InterruptedException;
  }

  private static final long NOT_WAITING = -1;
  private static final long CLAIMED = -2;

  private final long timeoutNanos;
  private final Runnable onIdle;
  private final Pace pace;
  private final long origin;

  /**
   * When the open wait began, in nanoseconds since {@code origin}; {@code NOT_WAITING} between
   * waits, {@code CLAIMED} once the watchdog took the wait. Measured from {@code origin}, a mark is
   * never negative, so it is never mistaken for either; two waits with equal marks began at the
   * same instant, so a claim of either is correct.
   */
  private final AtomicLong waitStart = new AtomicLong(NOT_WAITING);

  private Thread watchdog;
  private boolean stopped;

  /**
   * Watches waits against {@code timeoutNanos}, which must be positive.
   *
   * @param timeoutNanos the longest wait for a line
   * @param onIdle runs on the watchdog once it claimed a wait
   */
  IdleWatch(long timeoutNanos, Runnable onIdle) {
    this(timeoutNanos, onIdle, Pace.SYSTEM);
  }

  /** Watches waits as {@link #IdleWatch(long, Runnable)} does, by {@code pace}'s time and sleep. */
  IdleWatch(long timeoutNanos, Runnable onIdle, Pace pace) {
    this.timeoutNanos = timeoutNanos;
    this.onIdle = onIdle;
    this.pace = pace;
    this.origin = pace.nanoTime();
  }

  /** Marks the start of a wait, starting the watchdog on the first; returns the wait's mark. */
  long begin() {
    if (watchdog == null) {
      start();
    }
    var mark = pace.nanoTime() - origin;
    waitStart.set(mark);
    return mark;
  }

  /** Ends the wait {@code mark} began; {@code false} when the watchdog claimed it first. */
  boolean end(long mark) {
    return waitStart.compareAndSet(mark, NOT_WAITING);
  }

  /** Ends the watchdog, and keeps one from starting. */
  synchronized void stop() {
    stopped = true;
    if (watchdog != null) {
      watchdog.interrupt();
    }
  }

  /** The watchdog thread, or {@code null} before the first wait or once stopped before it. */
  synchronized Thread watchdog() {
    return watchdog;
  }

  private synchronized void start() {
    if (!stopped) {
      watchdog = Thread.ofVirtual().name("helios-sse-idle").start(this::watch);
    }
  }

  /**
   * Runs on the watchdog: sleeps until the open wait's deadline, or one timeout while none is open,
   * and claims a wait that outlasted the timeout. The thread ends once it has claimed a wait and
   * run {@code onIdle}, or when {@link #stop()} interrupts it.
   */
  private void watch() {
    try {
      while (true) {
        var mark = waitStart.get();
        var left =
            mark == NOT_WAITING ? timeoutNanos : timeoutNanos - (pace.nanoTime() - origin - mark);
        if (left <= 0 && waitStart.compareAndSet(mark, CLAIMED)) {
          onIdle.run();
          return;
        }
        pace.sleep(left);
      }
    } catch (InterruptedException stopping) {
      Thread.currentThread().interrupt();
    }
  }
}
