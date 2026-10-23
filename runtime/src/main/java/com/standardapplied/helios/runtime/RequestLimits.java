/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

/**
 * Bounds {@link AgentHttpService} applies to each request before doing work for it.
 *
 * <ul>
 *   <li>{@code maxRequestBytes}: 256 KiB. A message or interrupt body larger than this is answered
 *       {@code 413} unread when its {@code Content-Length} says so, and otherwise once one byte
 *       past the bound has been read.
 *   <li>{@code maxConcurrentRequestsPerPrincipal}: 32. Requests of one principal in flight at a
 *       time, on every route; one more is answered {@code 429} with {@code Retry-After} before any
 *       session lookup or body read. An open event stream holds its slot until it ends and a result
 *       poll until it is answered, even if the session is deleted meanwhile, so size this above the
 *       streams and polls a principal keeps open at its peak.
 * </ul>
 *
 * <p>Across principals, {@link RuntimeServer} serves at most 512 requests at once; Helidon answers
 * one more {@code 503} and closes its connection.
 *
 * @param maxRequestBytes largest accepted request body in bytes; positive, at most {@link
 *     #MAX_REQUEST_BYTES_CEILING}
 * @param maxConcurrentRequestsPerPrincipal most requests one principal has in flight at a time;
 *     positive
 */
public record RequestLimits(int maxRequestBytes, int maxConcurrentRequestsPerPrincipal) {

  /** Largest {@code maxRequestBytes} accepted: a bigger input belongs in a file, not a message. */
  public static final int MAX_REQUEST_BYTES_CEILING = 64 * 1024 * 1024;

  /**
   * Validate every bound.
   *
   * @throws IllegalArgumentException if any bound is not positive, or {@code maxRequestBytes}
   *     exceeds {@link #MAX_REQUEST_BYTES_CEILING}
   */
  public RequestLimits {
    requirePositive("maxRequestBytes", maxRequestBytes);
    if (maxRequestBytes > MAX_REQUEST_BYTES_CEILING) {
      throw new IllegalArgumentException(
          "maxRequestBytes must be at most "
              + MAX_REQUEST_BYTES_CEILING
              + ", got "
              + maxRequestBytes);
    }
    requirePositive("maxConcurrentRequestsPerPrincipal", maxConcurrentRequestsPerPrincipal);
  }

  /**
   * The production defaults listed above.
   *
   * @return the default limits
   */
  public static RequestLimits defaults() {
    return new RequestLimits(256 * 1024, 32);
  }

  private static void requirePositive(String name, int value) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive, got " + value);
    }
  }
}
