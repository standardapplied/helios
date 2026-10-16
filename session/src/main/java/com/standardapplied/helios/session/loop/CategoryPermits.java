/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The permits of one tool category: at most {@code capacity} calls run at once, admitted in arrival
 * order. The categories of one dispatcher share a {@link Scope}: a call {@link #quarantine
 * quarantined} while it still runs, because its caller stopped waiting for it, closes every
 * category of the scope, since a tool's category does not say which resources it touches. No call
 * is admitted until every quarantined call has been {@link #release released}. Admission,
 * quarantine and release share the scope's lock, so a quarantine never outlives the call it names.
 */
final class CategoryPermits {

  /** The quarantine, and the lock, shared by the categories of one dispatcher. */
  static final class Scope {
    private final Set<DispatchedCall> quarantined = new HashSet<>();
  }

  private final int capacity;
  private final Scope scope;
  private final ArrayDeque<DispatchedCall> queued = new ArrayDeque<>();
  private final Set<DispatchedCall> running = new HashSet<>();

  CategoryPermits(int capacity, Scope scope) {
    this.capacity = capacity;
    this.scope = scope;
  }

  /**
   * Wait until {@code call} is first in line, the category has room and nothing in the scope is
   * quarantined, then admit it if {@code start} says it may start.
   *
   * @return {@code true} if the call now holds a permit; {@code false} if {@code start} refused
   * @throws InterruptedException if the waiting thread is interrupted; the call holds no permit
   */
  boolean admit(DispatchedCall call, BooleanSupplier start) throws InterruptedException {
    synchronized (scope) {
      queued.addLast(call);
      try {
        while (queued.peekFirst() != call
            || running.size() >= capacity
            || !scope.quarantined.isEmpty()) {
          scope.wait();
        }
      } finally {
        queued.remove(call);
        scope.notifyAll();
      }
      if (!start.getAsBoolean()) {
        return false;
      }
      running.add(call);
      return true;
    }
  }

  /**
   * Close every category of the scope until {@code call} is released, if it still holds a permit.
   *
   * @return {@code true} if the call still holds a permit and is now quarantined
   */
  boolean quarantine(DispatchedCall call) {
    synchronized (scope) {
      var stillRunning = running.contains(call);
      if (stillRunning) {
        scope.quarantined.add(call);
      }
      return stillRunning;
    }
  }

  /** Give back {@code call}'s permit, and its quarantine, if it holds them. */
  void release(DispatchedCall call) {
    synchronized (scope) {
      running.remove(call);
      scope.quarantined.remove(call);
      scope.notifyAll();
    }
  }

  int available() {
    synchronized (scope) {
      return capacity - running.size();
    }
  }

  int queued() {
    synchronized (scope) {
      return queued.size();
    }
  }
}
