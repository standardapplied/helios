/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether the session's {@code ContextWarning} has fired. Sticky once fired, so the loop does not
 * re-emit a warning on every turn that stays above the watermark, until a successful compaction or
 * a history rewrite resets it and a future climb fires it again.
 */
final class ContextWatermark {

  private final AtomicBoolean fired = new AtomicBoolean(false);

  /**
   * Mark the warning as fired.
   *
   * @return {@code true} if this call flipped the flag (the caller emits the event); {@code false}
   *     if it was already set
   */
  boolean tryFire() {
    return fired.compareAndSet(false, true);
  }

  /** Clear the flag so a future climb back through the watermark fires the warning again. */
  void reset() {
    fired.set(false);
  }

  /** Whether the warning has fired since the last reset. */
  boolean fired() {
    return fired.get();
  }
}
