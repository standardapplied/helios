/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.session.tools.ToolBinding;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The permits of one tool category: at most {@code capacity} calls run at once, admitted in arrival
 * order. The categories of one dispatcher share a {@link Scope}: a call with side effects {@link
 * #quarantine quarantined} while it still runs, because its caller stopped waiting for it, closes
 * every category of the scope, since a tool's category does not say which resources it touches.
 * Until every quarantined call has been {@link #release released}, a call is refused rather than
 * admitted, and so is every call already queued. Admission, quarantine and release share the
 * scope's lock, so a quarantine never outlives the call it names.
 */
final class CategoryPermits {

  /** The quarantine, each call with its tool's name, and the lock, of one dispatcher. */
  static final class Scope {
    private final Map<DispatchedCall, String> quarantined = new LinkedHashMap<>();
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
   * Wait until {@code call} is first in line and the category has room, then admit it if {@code
   * start} says it may start. While the scope is quarantined, refuse it instead: {@code refuse}
   * gets the reason, outside the lock.
   *
   * @return {@code true} if the call now holds a permit; {@code false} if it was refused
   * @throws InterruptedException if the waiting thread is interrupted; the call holds no permit
   */
  boolean admit(DispatchedCall call, BooleanSupplier start, Consumer<String> refuse)
      throws InterruptedException {
    String stuck;
    synchronized (scope) {
      queued.addLast(call);
      try {
        while (scope.quarantined.isEmpty()
            && (queued.peekFirst() != call || running.size() >= capacity)) {
          scope.wait();
        }
      } finally {
        queued.remove(call);
        scope.notifyAll();
      }
      if (scope.quarantined.isEmpty()) {
        var admitted = start.getAsBoolean();
        if (admitted) {
          running.add(call);
        }
        return admitted;
      }
      stuck = scope.quarantined.values().iterator().next();
    }
    refuse.accept("tool '" + stuck + "' did not stop; no tool runs until it ends");
    return false;
  }

  /**
   * Close every category of the scope until {@code call} is released, if it still holds a permit
   * and {@code binding}'s category has side effects.
   *
   * @return {@code true} if the call still holds a permit
   */
  boolean quarantine(DispatchedCall call, ToolBinding binding) {
    synchronized (scope) {
      var stillRunning = running.contains(call);
      if (stillRunning && binding.category().hasSideEffects()) {
        scope.quarantined.put(call, binding.tool().name());
        scope.notifyAll();
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
