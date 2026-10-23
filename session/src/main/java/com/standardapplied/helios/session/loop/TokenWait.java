/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.runtime.CancellationToken;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * A wait on a latch or a future that also reads a {@link CancellationToken} every few milliseconds.
 * The token runs its callbacks one after another, so a callback registered earlier that blocks
 * would otherwise keep the callback that wakes the waiter from ever running.
 */
final class TokenWait {

  private static final long CHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

  private TokenWait() {}

  /**
   * Wait until {@code latch} opens or {@code token} is cancelled.
   *
   * @return {@code true} if {@code latch} opened, {@code false} if {@code token} was cancelled
   * @throws InterruptedException if the waiting thread is interrupted
   */
  static boolean opens(CountDownLatch latch, CancellationToken token) throws InterruptedException {
    return opensWithin(latch, token, () -> CHECK_NANOS);
  }

  /**
   * Wait until {@code latch} opens, {@code token} is cancelled or {@code until}, a {@link
   * System#nanoTime()} instant, passes.
   *
   * @return {@code true} if {@code latch} opened, {@code false} if {@code token} was cancelled or
   *     {@code until} passed first
   * @throws InterruptedException if the waiting thread is interrupted
   */
  static boolean opensBy(CountDownLatch latch, CancellationToken token, long until)
      throws InterruptedException {
    return opensWithin(latch, token, () -> until - System.nanoTime());
  }

  /**
   * Wait until {@code future} settles, normally or not, {@code token} is cancelled or {@code
   * until}, a {@link System#nanoTime()} instant, passes.
   *
   * @return {@code true} if {@code future} settled, {@code false} if {@code token} was cancelled or
   *     {@code until} passed first
   * @throws InterruptedException if the waiting thread is interrupted
   */
  static boolean settlesBy(CompletableFuture<?> future, CancellationToken token, long until)
      throws InterruptedException {
    var settled = new CountDownLatch(1);
    future.whenComplete((result, failure) -> settled.countDown());
    return opensBy(settled, token, until);
  }

  private static boolean opensWithin(
      CountDownLatch latch, CancellationToken token, LongSupplier remainingNanos)
      throws InterruptedException {
    while (!latch.await(
        Math.clamp(remainingNanos.getAsLong(), 0L, CHECK_NANOS), TimeUnit.NANOSECONDS)) {
      if (token.isCancelled() || remainingNanos.getAsLong() <= 0) {
        return false;
      }
    }
    return true;
  }
}
