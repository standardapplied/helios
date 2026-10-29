/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.trace;

/**
 * Receives each finished {@link Trace} once. An implementation shared by several producers must be
 * thread-safe; what happens when it throws is the producer's contract.
 */
@FunctionalInterface
public interface TraceListener {

  /**
   * Receives a finished trace.
   *
   * @param trace the finished trace
   */
  void onTrace(Trace trace);
}
