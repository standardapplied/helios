/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * The only place test code may wait with a limit, sleep, or read the clock. Every operation waits
 * for an event and ends in exactly two ways: the event, or {@link #HANG_GUARD}, whose expiry is an
 * {@link AssertionError} naming what was awaited. A test never chooses its own timeout, so its
 * outcome is the same on a machine a hundred times slower. An interrupt while waiting is a failure
 * too, and leaves the thread's interrupt status set.
 */
public final class Await {

  /** The one limit shared by every wait in the suite. Reaching it is always a failure. */
  public static final Duration HANG_GUARD = Duration.ofSeconds(60);

  private static final long POLL_MILLIS = 10;

  private Await() {}

  /** Waits until {@code latch} reaches zero. */
  public static void latch(String description, CountDownLatch latch) {
    latch(description, latch, HANG_GUARD);
  }

  static void latch(String description, CountDownLatch latch, Duration limit) {
    guarded(description, limit, nanos -> happened(latch.await(nanos, TimeUnit.NANOSECONDS)));
  }

  /** Returns the value {@code future} completes with; a failed or cancelled future fails. */
  public static <T> T value(String description, Future<T> future) {
    return value(description, future, HANG_GUARD);
  }

  static <T> T value(String description, Future<T> future, Duration limit) {
    return guarded(
        description,
        limit,
        nanos -> {
          try {
            return future.get(nanos, TimeUnit.NANOSECONDS);
          } catch (ExecutionException e) {
            throw new AssertionError(description + " failed instead of completing", e.getCause());
          } catch (CancellationException e) {
            throw new AssertionError(description + " was cancelled instead of completing", e);
          }
        });
  }

  /**
   * Returns the exception {@code future} completes with: the cause it failed with, or the {@link
   * CancellationException} of a cancelled future. Where the JDK reports a cancellation through a
   * fresh wrapper, the original it wraps is returned. A future that completes with a value fails.
   */
  public static Throwable failure(String description, Future<?> future) {
    return failure(description, future, HANG_GUARD);
  }

  static Throwable failure(String description, Future<?> future, Duration limit) {
    return guarded(
        description,
        limit,
        nanos -> {
          Object value;
          try {
            value = future.get(nanos, TimeUnit.NANOSECONDS);
          } catch (ExecutionException e) {
            return e.getCause();
          } catch (CancellationException e) {
            return e.getCause() instanceof CancellationException original ? original : e;
          }
          throw new AssertionError(
              description + " completed with " + value + " instead of failing");
        });
  }

  /** Takes the next element of {@code queue}. */
  public static <T> T next(String description, BlockingQueue<T> queue) {
    return next(description, queue, HANG_GUARD);
  }

  static <T> T next(String description, BlockingQueue<T> queue, Duration limit) {
    return guarded(
        description,
        limit,
        nanos -> {
          var element = queue.poll(nanos, TimeUnit.NANOSECONDS);
          happened(element != null);
          return element;
        });
  }

  /** Waits until {@code thread} has terminated. */
  public static void termination(String description, Thread thread) {
    termination(description, thread, HANG_GUARD);
  }

  static void termination(String description, Thread thread, Duration limit) {
    guarded(description, limit, nanos -> happened(thread.join(Duration.ofNanos(nanos))));
  }

  /** Waits until {@code executor}, already shut down by the caller, has terminated. */
  public static void termination(String description, ExecutorService executor) {
    termination(description, executor, HANG_GUARD);
  }

  static void termination(String description, ExecutorService executor, Duration limit) {
    guarded(
        description,
        limit,
        nanos -> happened(executor.awaitTermination(nanos, TimeUnit.NANOSECONDS)));
  }

  /** Waits until {@code process} has exited. */
  public static void termination(String description, Process process) {
    termination(description, process, HANG_GUARD);
  }

  static void termination(String description, Process process, Duration limit) {
    guarded(description, limit, nanos -> happened(process.waitFor(nanos, TimeUnit.NANOSECONDS)));
  }

  /**
   * Polls {@code condition} until it holds. The last resort, for state that offers no event to wait
   * on; prefer a latch, a future or a queue.
   */
  public static void until(String description, BooleanSupplier condition) {
    until(description, condition, HANG_GUARD);
  }

  static void until(String description, BooleanSupplier condition, Duration limit) {
    guarded(
        description,
        limit,
        nanos -> {
          var deadline = System.nanoTime() + nanos;
          while (!condition.getAsBoolean()) {
            happened(System.nanoTime() - deadline < 0);
            Thread.sleep(POLL_MILLIS);
          }
          return null;
        });
  }

  private static <T> T guarded(String description, Duration limit, GuardedWait<T> wait) {
    try {
      return wait.await(limit.toNanos());
    } catch (TimeoutException e) {
      throw new AssertionError("Still waiting for " + description + " after " + limit, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for " + description, e);
    }
  }

  private static Void happened(boolean event) throws TimeoutException {
    if (!event) {
      throw new TimeoutException();
    }
    return null;
  }

  @FunctionalInterface
  private interface GuardedWait<T> {
    T await(long nanos) throws InterruptedException, TimeoutException;
  }
}
