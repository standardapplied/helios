/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

/**
 * Bounds {@link AgentHttpService} applies to each request before doing work for it.
 *
 * <ul>
 *   <li>{@code maxRequestBytes}: 256 KiB. A message or interrupt body larger than this is answered
 *       {@code 413} unread when its {@code Content-Length} says so, and otherwise once one byte
 *       past the bound has been read.
 *   <li>{@code maxEventStreamsPerSession}: 4. Further {@code GET .../events} on the session are
 *       answered {@code 429} until one of its streams ends.
 *   <li>{@code maxResultPollsPerSession}: 4. Further {@code GET .../result} on the session are
 *       answered {@code 429} until one of its polls is answered.
 * </ul>
 *
 * <p>Together with the {@link SessionRegistry}'s caps the per-session bounds bound every
 * principal's open streams and polls, and the whole process's, without one principal using up
 * another's.
 *
 * @param maxRequestBytes largest accepted request body in bytes; positive, at most {@link
 *     #MAX_REQUEST_BYTES_CEILING}
 * @param maxEventStreamsPerSession most event streams open on one session at a time; positive
 * @param maxResultPollsPerSession most result polls waiting on one session at a time; positive
 */
public record RequestLimits(
    int maxRequestBytes, int maxEventStreamsPerSession, int maxResultPollsPerSession) {

  /** Largest {@code maxRequestBytes} accepted: a bigger input belongs in a file, not a message. */
  public static final int MAX_REQUEST_BYTES_CEILING = 64 * 1024 * 1024;

  private static final RequestLimits DEFAULTS = new RequestLimits(256 * 1024, 4, 4);

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
    requirePositive("maxEventStreamsPerSession", maxEventStreamsPerSession);
    requirePositive("maxResultPollsPerSession", maxResultPollsPerSession);
  }

  /**
   * The production defaults listed above.
   *
   * @return the default limits
   */
  public static RequestLimits defaults() {
    return DEFAULTS;
  }

  /**
   * Start building limits from the defaults.
   *
   * @return a fresh builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  private static void requirePositive(String name, int value) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive, got " + value);
    }
  }

  /** Fluent builder for {@link RequestLimits}, starting from the defaults. */
  public static final class Builder {

    private int maxRequestBytes = DEFAULTS.maxRequestBytes();
    private int maxEventStreamsPerSession = DEFAULTS.maxEventStreamsPerSession();
    private int maxResultPollsPerSession = DEFAULTS.maxResultPollsPerSession();

    private Builder() {}

    /**
     * Set the largest accepted request body.
     *
     * @param maxRequestBytes positive byte count
     * @return this builder
     */
    public Builder withMaxRequestBytes(int maxRequestBytes) {
      this.maxRequestBytes = maxRequestBytes;
      return this;
    }

    /**
     * Set the most event streams open on one session at a time.
     *
     * @param maxEventStreamsPerSession positive count
     * @return this builder
     */
    public Builder withMaxEventStreamsPerSession(int maxEventStreamsPerSession) {
      this.maxEventStreamsPerSession = maxEventStreamsPerSession;
      return this;
    }

    /**
     * Set the most result polls waiting on one session at a time.
     *
     * @param maxResultPollsPerSession positive count
     * @return this builder
     */
    public Builder withMaxResultPollsPerSession(int maxResultPollsPerSession) {
      this.maxResultPollsPerSession = maxResultPollsPerSession;
      return this;
    }

    /**
     * Build the limits.
     *
     * @return the limits
     * @throws IllegalArgumentException if any bound is out of range
     */
    public RequestLimits build() {
      return new RequestLimits(
          maxRequestBytes, maxEventStreamsPerSession, maxResultPollsPerSession);
    }
  }
}
