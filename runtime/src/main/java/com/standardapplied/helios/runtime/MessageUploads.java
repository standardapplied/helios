/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import io.helidon.http.Status;
import io.helidon.webserver.http.ServerResponse;
import java.util.concurrent.Semaphore;

/**
 * Bounds the message and interrupt requests being read and queued at a time, per principal and
 * across the service, so their bodies never buffer more than the service-wide bound times {@link
 * RequestLimits#maxRequestBytes()}. An upload is admitted before its first byte is read and holds
 * its admission until it is answered, whatever happens to its session meanwhile. The principal's
 * own bound is checked first, so a principal at it is refused the same whatever others upload.
 * Thread-safe.
 */
final class MessageUploads {

  private final KeyedSlots<Principal> perPrincipal;
  private final Semaphore total;

  MessageUploads(int maxUploads, int maxUploadsPerPrincipal) {
    this.perPrincipal = new KeyedSlots<>(maxUploadsPerPrincipal);
    this.total = new Semaphore(maxUploads);
  }

  /**
   * Admit one upload of {@code principal}, or answer {@code 429} when the principal is at its bound
   * and {@code 503} when the service is. An admitted upload is given back through {@link
   * #release(Principal)}.
   *
   * @return whether the upload was admitted; when not, {@code resp} has been answered
   */
  boolean admit(Principal principal, ServerResponse resp) {
    if (!perPrincipal.tryAcquire(principal)) {
      Overload.reject(resp, Status.TOO_MANY_REQUESTS_429, Overload.TOO_MANY_UPLOADS);
      return false;
    }
    if (!total.tryAcquire()) {
      perPrincipal.release(principal);
      Overload.reject(resp, Status.SERVICE_UNAVAILABLE_503, Overload.SERVER_AT_UPLOAD_CAPACITY);
      return false;
    }
    return true;
  }

  /** Give back an upload of {@code principal} that {@link #admit} admitted. */
  void release(Principal principal) {
    total.release();
    perPrincipal.release(principal);
  }
}
