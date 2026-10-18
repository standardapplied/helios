/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import java.util.concurrent.CountDownLatch;

/**
 * Blocks until a latch opens, through every interrupt, as code that ignores interruption does; the
 * interrupt status is restored before it returns. A hanging fixture blocks here on a latch the test
 * opens when it ends.
 */
public final class Uninterruptible {

  private Uninterruptible() {}

  public static void await(CountDownLatch latch) {
    var interrupted = false;
    while (latch.getCount() > 0) {
      try {
        latch.await();
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
