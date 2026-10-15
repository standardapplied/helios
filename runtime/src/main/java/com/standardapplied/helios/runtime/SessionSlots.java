/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import io.helidon.http.Status;
import io.helidon.webserver.http.ServerResponse;
import java.util.Map;

/**
 * Bounds how many requests of one kind hold one session at a time, then admits them against the
 * kind's {@link ConcurrencyLimit} per principal and across the service, and counts what it admits
 * and refuses. The session's bound is checked first. Thread-safe.
 */
final class SessionSlots {

  private final KeyedSlots<String> slots;
  private final Map<String, String> sessionRefusal;
  private final ConcurrencyLimit limit;
  private final AdmissionCounter counter = new AdmissionCounter();

  private SessionSlots(int perSession, Map<String, String> sessionRefusal, ConcurrencyLimit limit) {
    this.slots = new KeyedSlots<>(perSession);
    this.sessionRefusal = sessionRefusal;
    this.limit = limit;
  }

  /** Event streams, bounded as {@link RequestLimits} says. */
  static SessionSlots eventStreams(RequestLimits limits) {
    return new SessionSlots(
        limits.maxEventStreamsPerSession(),
        Overload.TOO_MANY_EVENT_STREAMS,
        ConcurrencyLimit.eventStreams(limits));
  }

  /** Result polls, bounded as {@link RequestLimits} says. */
  static SessionSlots resultPolls(RequestLimits limits) {
    return new SessionSlots(
        limits.maxResultPollsPerSession(),
        Overload.TOO_MANY_RESULT_POLLS,
        ConcurrencyLimit.resultPolls(limits));
  }

  /**
   * Admit one request of {@code principal} on {@code sessionId}, or answer {@code 429} when the
   * session or the principal is at its bound and {@code 503} when the service is. An admitted
   * request is given back through {@link #release(String, Principal)}.
   *
   * @return whether the request was admitted; when not, {@code resp} has been answered
   */
  boolean admit(String sessionId, Principal principal, ServerResponse resp) {
    if (!slots.tryAcquire(sessionId)) {
      counter.reject();
      Overload.reject(resp, Status.TOO_MANY_REQUESTS_429, sessionRefusal);
      return false;
    }
    var admitted = false;
    try {
      admitted = limit.admit(principal, resp);
      return admitted;
    } finally {
      settle(sessionId, admitted);
    }
  }

  /** Count the outcome; unless admitted, give the session's slot back, even if refusing threw. */
  private void settle(String sessionId, boolean admitted) {
    if (admitted) {
      counter.admit();
    } else {
      slots.release(sessionId);
      counter.reject();
    }
  }

  /** Give back a request that {@link #admit} admitted. */
  void release(String sessionId, Principal principal) {
    limit.release(principal);
    slots.release(sessionId);
  }

  AdmissionCounts counts() {
    return counter.counts();
  }
}
