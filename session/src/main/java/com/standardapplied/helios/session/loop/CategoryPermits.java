/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The permits of one tool category: at most {@code capacity} calls run at once, admitted in arrival
 * order. A call {@link #quarantine quarantined} while it still runs, because its caller stopped
 * waiting for it, closes the category: no call is admitted until every quarantined call has been
 * {@link #release released}. Admission, quarantine and release share one lock, so a quarantine
 * never outlives the call it names.
 */
final class CategoryPermits {

  private final int capacity;
  private final ArrayDeque<DispatchedCall> queued = new ArrayDeque<>();
  private final Set<DispatchedCall> running = new HashSet<>();
  private final Set<DispatchedCall> quarantined = new HashSet<>();

  CategoryPermits(int capacity) {
    this.capacity = capacity;
  }

  /**
   * Wait until {@code call} is first in line, the category has room and nothing is quarantined,
   * then admit it if {@code start} says it may start.
   *
   * @return {@code true} if the call now holds a permit; {@code false} if {@code start} refused
   * @throws InterruptedException if the waiting thread is interrupted; the call holds no permit
   */
  synchronized boolean admit(DispatchedCall call, BooleanSupplier start)
      throws InterruptedException {
    queued.addLast(call);
    try {
      while (queued.peekFirst() != call || running.size() >= capacity || !quarantined.isEmpty()) {
        wait();
      }
    } finally {
      queued.remove(call);
      notifyAll();
    }
    if (!start.getAsBoolean()) {
      return false;
    }
    running.add(call);
    return true;
  }

  /**
   * Close the category until {@code call} is released, if it still holds a permit.
   *
   * @return {@code true} if the call still holds a permit and is now quarantined
   */
  synchronized boolean quarantine(DispatchedCall call) {
    var stillRunning = running.contains(call);
    if (stillRunning) {
      quarantined.add(call);
    }
    return stillRunning;
  }

  /** Give back {@code call}'s permit, and its quarantine, if it holds them. */
  synchronized void release(DispatchedCall call) {
    running.remove(call);
    quarantined.remove(call);
    notifyAll();
  }

  synchronized int available() {
    return capacity - running.size();
  }

  synchronized int queued() {
    return queued.size();
  }
}
