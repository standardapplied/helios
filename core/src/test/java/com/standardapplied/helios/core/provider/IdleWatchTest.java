/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdleWatchTest {

  private static final long TIMEOUT = 1_000;

  /**
   * A clock only the test advances, and a sleep that waits for the test to wake it; a sleep that is
   * not positive returns at once, as {@link IdleWatch.Pace} requires. The next clock read can be
   * held, so the test acts between the watchdog's read of a wait and its claim.
   */
  private static final class FakePace implements IdleWatch.Pace {
    private final BlockingQueue<Long> sleeps = new LinkedBlockingQueue<>();
    private final Semaphore wakes = new Semaphore(0);
    private volatile long now;
    private volatile CountDownLatch[] hold;

    @Override
    public long nanoTime() {
      var held = hold;
      if (held != null) {
        hold = null;
        held[0].countDown();
        Uninterruptible.await(held[1]);
      }
      return now;
    }

    @Override
    public void sleep(long nanos) throws InterruptedException {
      if (nanos > 0) {
        sleeps.add(nanos);
        wakes.acquire();
      }
    }

    /** How long the watchdog's next sleep asked for, once it sleeps. */
    long nextSleep() {
      return Await.next("the watchdog to sleep", sleeps);
    }

    /** Moves the clock {@code nanos} on and wakes the sleeping watchdog. */
    void advanceAndWake(long nanos) {
      now += nanos;
      wakes.release();
    }

    /** Holds the next clock read: counts {@code reached} down, then waits for {@code released}. */
    void holdNextRead(CountDownLatch reached, CountDownLatch released) {
      hold = new CountDownLatch[] {reached, released};
    }
  }

  private final FakePace pace = new FakePace();
  private final AtomicInteger idle = new AtomicInteger();
  private final IdleWatch watch = new IdleWatch(TIMEOUT, idle::incrementAndGet, pace);

  @Test
  void theFirstWaitStartsOneNamedWatchdogThatEveryLaterWaitKeeps() {
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
    watch.stop();
    watch.begin();

    assertNull(watch.watchdog());
  }

  @Test
  void betweenWaitsTheWatchdogSleepsOneTimeoutAtATimeInsteadOfSpinning() {
    assertTrue(watch.end(watch.begin()));

    assertEquals(TIMEOUT, pace.nextSleep());
    pace.advanceAndWake(0);
    assertEquals(TIMEOUT, pace.nextSleep());
    pace.advanceAndWake(TIMEOUT);
    assertEquals(TIMEOUT, pace.nextSleep());
    assertEquals(0, idle.get());
    stopAndAwaitTheWatchdog();
  }

  @Test
  void timeBetweenWaitsIsNeverIdle() {
    assertTrue(watch.end(watch.begin()));
    assertEquals(TIMEOUT, pace.nextSleep());

    pace.advanceAndWake(10 * TIMEOUT);
    assertEquals(TIMEOUT, pace.nextSleep());
    var mark = watch.begin();

    assertTrue(watch.end(mark));
    assertEquals(0, idle.get());
    stopAndAwaitTheWatchdog();
  }

  @Test
  void waitsThatEachEndInsideTheTimeoutNeverTimeOutOverManyTimeouts() {
    var mark = watch.begin();
    assertEquals(TIMEOUT, pace.nextSleep());

    for (var i = 0; i < 10; i++) {
      pace.advanceAndWake(TIMEOUT - TIMEOUT / 10);
      assertEquals(TIMEOUT / 10, pace.nextSleep());
      assertTrue(watch.end(mark));
      mark = watch.begin();
    }

    assertEquals(0, idle.get());
    stopAndAwaitTheWatchdog();
  }

  @Test
  void aWaitIsClaimedAtItsDeadlineAndNotBefore() {
    var mark = watch.begin();
    assertEquals(TIMEOUT, pace.nextSleep());

    pace.advanceAndWake(TIMEOUT - 1);
    assertEquals(1, pace.nextSleep());
    assertEquals(0, idle.get());
    pace.advanceAndWake(1);

    Await.termination("the watchdog to claim the wait and end", watch.watchdog());
    assertEquals(1, idle.get());
    assertFalse(watch.end(mark));
  }

  @Test
  void aWaitEndedWhileTheWatchdogDecidesCannotBeClaimed() {
    var mark = watch.begin();
    assertEquals(TIMEOUT, pace.nextSleep());
    var reached = new CountDownLatch(1);
    var released = new CountDownLatch(1);
    pace.holdNextRead(reached, released);
    pace.advanceAndWake(TIMEOUT);
    Await.latch("the watchdog to read the clock for the open wait", reached);

    assertTrue(watch.end(mark));
    released.countDown();

    assertEquals(TIMEOUT, pace.nextSleep());
    assertEquals(0, idle.get());
    stopAndAwaitTheWatchdog();
  }

  private void stopAndAwaitTheWatchdog() {
    watch.stop();
    Await.termination("the watchdog", watch.watchdog());
  }
}
