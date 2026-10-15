/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounds how many requests of one kind hold one session at a time, and counts what it admits and
 * refuses. A session has an entry only while a request holds it, so the slots of a closed session
 * take no memory. Thread-safe: each acquire and release is one atomic update of the session's
 * entry.
 */
final class SessionSlots {

  private final int perSession;
  private final ConcurrentHashMap<String, Integer> held = new ConcurrentHashMap<>();
  private final AdmissionCounter counter = new AdmissionCounter();

  SessionSlots(int perSession) {
    this.perSession = perSession;
  }

  /** Take a slot of {@code sessionId}, or refuse if all of its slots are held. */
  boolean tryAcquire(String sessionId) {
    var acquired = new AtomicBoolean();
    held.compute(
        sessionId,
        (id, count) -> {
          var holders = count == null ? 0 : count;
          if (holders >= perSession) {
            return count;
          }
          acquired.set(true);
          return holders + 1;
        });
    if (acquired.get()) {
      counter.admit();
    } else {
      counter.reject();
    }
    return acquired.get();
  }

  /** Give back a slot of {@code sessionId} taken by {@link #tryAcquire(String)}. */
  void release(String sessionId) {
    held.computeIfPresent(sessionId, (id, count) -> count == 1 ? null : count - 1);
  }

  AdmissionCounts counts() {
    return counter.counts();
  }
}
