/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import java.util.concurrent.atomic.LongAdder;

/** Counts the admissions and refusals of one kind of request. Thread-safe. */
final class AdmissionCounter {

  private final LongAdder admitted = new LongAdder();
  private final LongAdder rejected = new LongAdder();

  void admit() {
    admitted.increment();
  }

  void reject() {
    rejected.increment();
  }

  AdmissionCounts counts() {
    return new AdmissionCounts(admitted.sum(), rejected.sum());
  }
}
