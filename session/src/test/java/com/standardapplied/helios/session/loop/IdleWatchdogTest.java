/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class IdleWatchdogTest {

  /**
   * A concurrent {@code arm()} that slots its deadline while this one is scheduling wins the slot,
   * and this arm cancels the deadline it just scheduled, so exactly one stays pending.
   */
  @Test
  void anArmThatLosesTheSlotToAConcurrentArmCancelsItsOwnDeadline() {
    var watchdog = new AtomicReference<IdleWatchdog>();
    var scheduler = new ReenteringScheduler(() -> watchdog.get().arm());
    try {
      watchdog.set(new IdleWatchdog(scheduler, Duration.ofHours(1), () -> false, e -> {}));

      watchdog.get().arm();

      assertEquals(2, scheduler.futures.size());
      assertTrue(scheduler.futures.get(0).isCancelled(), "the losing arm's deadline");
      assertFalse(scheduler.futures.get(1).isCancelled(), "the winning arm's deadline");
      watchdog.get().cancel();
      assertTrue(scheduler.futures.get(1).isCancelled());
    } finally {
      scheduler.shutdownNow();
    }
  }

  /** Runs a concurrent arm, once, while the first task is being scheduled. */
  private static final class ReenteringScheduler extends ScheduledThreadPoolExecutor {

    final List<RunnableScheduledFuture<?>> futures = new CopyOnWriteArrayList<>();
    private final Runnable concurrentArm;
    private final AtomicBoolean reentered = new AtomicBoolean();

    ReenteringScheduler(Runnable concurrentArm) {
      super(1);
      this.concurrentArm = concurrentArm;
    }

    @Override
    protected <V> RunnableScheduledFuture<V> decorateTask(
        Runnable runnable, RunnableScheduledFuture<V> task) {
      futures.add(task);
      if (reentered.compareAndSet(false, true)) {
        concurrentArm.run();
      }
      return task;
    }
  }
}
