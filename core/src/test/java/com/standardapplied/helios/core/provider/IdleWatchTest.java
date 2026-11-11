/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class IdleWatchTest {

  private static final long NEVER = Long.MAX_VALUE;

  @Test
  void theFirstWaitStartsOneNamedWatchdogThatEveryLaterWaitKeeps() {
    var watch = new IdleWatch(NEVER, () -> {});

    assertTrue(watch.end(watch.begin()));
    var watchdog = watch.watchdog();
    assertTrue(watch.end(watch.begin()));

    assertSame(watchdog, watch.watchdog());
    assertEquals("helios-sse-idle", watchdog.getName());
    watch.stop();
    Await.termination("the watchdog", watchdog);
  }

  @Test
  void aWatchStoppedBeforeItsFirstWaitNeverStartsAWatchdog() {
    var watch = new IdleWatch(NEVER, () -> {});

    watch.stop();
    watch.begin();

    assertNull(watch.watchdog());
  }

  @Test
  void aWaitTheWatchdogClaimedCannotBeEndedAndTheWatchdogEndsAfterRunningOnIdle() {
    var idle = new CountDownLatch(1);
    var watch = new IdleWatch(1, idle::countDown);

    var mark = watch.begin();
    Await.latch("the watchdog to claim the wait", idle);

    assertFalse(watch.end(mark));
    Await.termination("the watchdog", watch.watchdog());
  }
}
