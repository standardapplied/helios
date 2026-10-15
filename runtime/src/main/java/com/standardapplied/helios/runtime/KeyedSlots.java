/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounds how many requests hold one key, a session or a principal, at a time. A key has an entry
 * only while a request holds it, so the slots of a closed session or an idle principal take no
 * memory. Thread-safe: each acquire and release is one atomic update of the key's entry.
 *
 * @param <K> the key the bound applies to
 */
final class KeyedSlots<K> {

  private final int perKey;
  private final ConcurrentHashMap<K, Integer> held = new ConcurrentHashMap<>();

  KeyedSlots(int perKey) {
    this.perKey = perKey;
  }

  /** Take a slot of {@code key}, or refuse if all of its slots are held. */
  boolean tryAcquire(K key) {
    var acquired = new AtomicBoolean();
    held.compute(
        key,
        (k, count) -> {
          var holders = count == null ? 0 : count;
          if (holders >= perKey) {
            return count;
          }
          acquired.set(true);
          return holders + 1;
        });
    return acquired.get();
  }

  /** Give back a slot of {@code key} taken by {@link #tryAcquire(Object)}. */
  void release(K key) {
    held.computeIfPresent(key, (k, count) -> count == 1 ? null : count - 1);
  }

  /** How many keys have an entry; package-private for tests. */
  int heldKeys() {
    return held.size();
  }
}
