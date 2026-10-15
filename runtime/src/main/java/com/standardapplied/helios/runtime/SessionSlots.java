/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

/**
 * Bounds how many requests of one kind hold one session at a time, and counts what it admits and
 * refuses. Thread-safe.
 */
final class SessionSlots {

  private final KeyedSlots<String> slots;
  private final AdmissionCounter counter = new AdmissionCounter();

  SessionSlots(int perSession) {
    this.slots = new KeyedSlots<>(perSession);
  }

  /** Take a slot of {@code sessionId}, or refuse if all of its slots are held. */
  boolean tryAcquire(String sessionId) {
    var acquired = slots.tryAcquire(sessionId);
    if (acquired) {
      counter.admit();
    } else {
      counter.reject();
    }
    return acquired;
  }

  /** Give back a slot of {@code sessionId} taken by {@link #tryAcquire(String)}. */
  void release(String sessionId) {
    slots.release(sessionId);
  }

  AdmissionCounts counts() {
    return counter.counts();
  }
}
