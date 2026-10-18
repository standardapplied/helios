/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class UninterruptibleTest {

  @Test
  void waitsThroughAnInterruptUntilTheLatchOpensAndKeepsTheInterrupt() {
    var latch = new CountDownLatch(1);
    var ended = new CompletableFuture<List<Boolean>>();
    var waiter =
        Thread.ofVirtual()
            .start(
                () -> {
                  Uninterruptible.await(latch);
                  ended.complete(
                      List.of(latch.getCount() == 0, Thread.currentThread().isInterrupted()));
                });
    Await.until("the waiter to block", () -> waiter.getState() == Thread.State.WAITING);

    waiter.interrupt();
    Await.until(
        "the waiter to take the interrupt and block again",
        () -> !waiter.isInterrupted() && waiter.getState() == Thread.State.WAITING);
    latch.countDown();

    assertEquals(List.of(true, true), Await.value("the waiter to return", ended));
  }

  @Test
  void returnsAtOnceWithTheInterruptStatusUntouchedWhenTheLatchIsOpen() {
    Uninterruptible.await(new CountDownLatch(0));

    assertFalse(Thread.currentThread().isInterrupted());
  }
}
