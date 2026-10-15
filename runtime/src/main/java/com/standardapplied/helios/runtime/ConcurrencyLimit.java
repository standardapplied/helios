/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import io.helidon.http.Status;
import io.helidon.webserver.http.ServerResponse;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Bounds the requests of one kind in progress at a time, per principal and across the service. A
 * request is admitted before its work starts and holds its admission until it is answered, whatever
 * happens to its session meanwhile, so deleting and recreating sessions never frees room. The
 * principal's own bound is checked first, so a principal at it is refused the same whatever others
 * do. Thread-safe.
 */
final class ConcurrencyLimit {

  private final KeyedSlots<Principal> perPrincipal;
  private final Semaphore total;
  private final Map<String, String> principalRefusal;
  private final Map<String, String> serverRefusal;

  private ConcurrencyLimit(
      int max,
      int maxPerPrincipal,
      Map<String, String> principalRefusal,
      Map<String, String> serverRefusal) {
    this.perPrincipal = new KeyedSlots<>(maxPerPrincipal);
    this.total = new Semaphore(max);
    this.principalRefusal = principalRefusal;
    this.serverRefusal = serverRefusal;
  }

  /** Message and interrupt requests being read and queued. */
  static ConcurrencyLimit messageUploads(RequestLimits limits) {
    return new ConcurrencyLimit(
        limits.maxMessageUploads(),
        limits.maxMessageUploadsPerPrincipal(),
        Overload.TOO_MANY_UPLOADS,
        Overload.SERVER_AT_UPLOAD_CAPACITY);
  }

  /** Open event streams. */
  static ConcurrencyLimit eventStreams(RequestLimits limits) {
    return new ConcurrencyLimit(
        limits.maxEventStreams(),
        limits.maxEventStreamsPerPrincipal(),
        Overload.TOO_MANY_OPEN_EVENT_STREAMS,
        Overload.SERVER_AT_EVENT_STREAM_CAPACITY);
  }

  /** Result polls waiting or being answered. */
  static ConcurrencyLimit resultPolls(RequestLimits limits) {
    return new ConcurrencyLimit(
        limits.maxResultPolls(),
        limits.maxResultPollsPerPrincipal(),
        Overload.TOO_MANY_WAITING_RESULT_POLLS,
        Overload.SERVER_AT_RESULT_POLL_CAPACITY);
  }

  /**
   * Admit one request of {@code principal}, or answer {@code 429} when the principal is at its
   * bound and {@code 503} when the service is. An admitted request is given back through {@link
   * #release(Principal)}.
   *
   * @return whether the request was admitted; when not, {@code resp} has been answered
   */
  boolean admit(Principal principal, ServerResponse resp) {
    if (!perPrincipal.tryAcquire(principal)) {
      Overload.reject(resp, Status.TOO_MANY_REQUESTS_429, principalRefusal);
      return false;
    }
    if (!total.tryAcquire()) {
      perPrincipal.release(principal);
      Overload.reject(resp, Status.SERVICE_UNAVAILABLE_503, serverRefusal);
      return false;
    }
    return true;
  }

  /** Give back a request of {@code principal} that {@link #admit} admitted. */
  void release(Principal principal) {
    total.release();
    perPrincipal.release(principal);
  }
}
