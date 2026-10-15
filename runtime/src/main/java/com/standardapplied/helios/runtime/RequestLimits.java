/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

/**
 * Bounds {@link AgentHttpService} applies to each request before doing work for it.
 *
 * <ul>
 *   <li>{@code maxRequestBytes}: 256 KiB. A message or interrupt body larger than this is answered
 *       {@code 413} unread when its {@code Content-Length} says so, and otherwise once one byte
 *       past the bound has been read.
 *   <li>{@code maxMessageUploads}: 256. Message and interrupt requests being read and queued at a
 *       time across the service; one more is answered {@code 503}. With {@code maxRequestBytes}
 *       this bounds the bodies buffered at once (64 MiB at the defaults).
 *   <li>{@code maxMessageUploadsPerPrincipal}: 16. The same, per principal; one more is answered
 *       {@code 429}, so one principal cannot take the whole service-wide bound.
 *   <li>{@code maxEventStreams}: 256. Event streams open at a time across the service; one more is
 *       answered {@code 503}.
 *   <li>{@code maxEventStreamsPerPrincipal}: 16. The same, per principal; one more is answered
 *       {@code 429}.
 *   <li>{@code maxEventStreamsPerSession}: 4. The same, per session; one more is answered {@code
 *       429}.
 *   <li>{@code maxResultPolls}: 256. Result polls waiting or being answered at a time across the
 *       service; one more is answered {@code 503}.
 *   <li>{@code maxResultPollsPerPrincipal}: 16. The same, per principal; one more is answered
 *       {@code 429}.
 *   <li>{@code maxResultPollsPerSession}: 4. The same, per session; one more is answered {@code
 *       429}.
 * </ul>
 *
 * <p>A stream or poll holds its admission until it ends, even if its session is deleted or evicted
 * meanwhile, so session churn never lifts the bounds. A principal's bound is checked before the
 * service's, so one principal at its own bound is refused the same whatever others hold.
 *
 * @param maxRequestBytes largest accepted request body in bytes; positive, at most {@link
 *     #MAX_REQUEST_BYTES_CEILING}
 * @param maxMessageUploads most message and interrupt requests read and queued at a time across the
 *     service; positive
 * @param maxMessageUploadsPerPrincipal most message and interrupt requests one principal has read
 *     and queued at a time; positive
 * @param maxEventStreams most event streams open at a time across the service; positive
 * @param maxEventStreamsPerPrincipal most event streams one principal has open at a time; positive
 * @param maxEventStreamsPerSession most event streams open on one session at a time; positive
 * @param maxResultPolls most result polls in progress at a time across the service; positive
 * @param maxResultPollsPerPrincipal most result polls one principal has in progress at a time;
 *     positive
 * @param maxResultPollsPerSession most result polls in progress on one session at a time; positive
 */
public record RequestLimits(
    int maxRequestBytes,
    int maxMessageUploads,
    int maxMessageUploadsPerPrincipal,
    int maxEventStreams,
    int maxEventStreamsPerPrincipal,
    int maxEventStreamsPerSession,
    int maxResultPolls,
    int maxResultPollsPerPrincipal,
    int maxResultPollsPerSession) {

  /** Largest {@code maxRequestBytes} accepted: a bigger input belongs in a file, not a message. */
  public static final int MAX_REQUEST_BYTES_CEILING = 64 * 1024 * 1024;

  private static final RequestLimits DEFAULTS =
      new RequestLimits(256 * 1024, 256, 16, 256, 16, 4, 256, 16, 4);

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
    requirePositive("maxMessageUploads", maxMessageUploads);
    requirePositive("maxMessageUploadsPerPrincipal", maxMessageUploadsPerPrincipal);
    requirePositive("maxEventStreams", maxEventStreams);
    requirePositive("maxEventStreamsPerPrincipal", maxEventStreamsPerPrincipal);
    requirePositive("maxEventStreamsPerSession", maxEventStreamsPerSession);
    requirePositive("maxResultPolls", maxResultPolls);
    requirePositive("maxResultPollsPerPrincipal", maxResultPollsPerPrincipal);
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
    private int maxMessageUploads = DEFAULTS.maxMessageUploads();
    private int maxMessageUploadsPerPrincipal = DEFAULTS.maxMessageUploadsPerPrincipal();
    private int maxEventStreams = DEFAULTS.maxEventStreams();
    private int maxEventStreamsPerPrincipal = DEFAULTS.maxEventStreamsPerPrincipal();
    private int maxEventStreamsPerSession = DEFAULTS.maxEventStreamsPerSession();
    private int maxResultPolls = DEFAULTS.maxResultPolls();
    private int maxResultPollsPerPrincipal = DEFAULTS.maxResultPollsPerPrincipal();
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
     * Set the most message and interrupt requests read and queued at a time across the service.
     *
     * @param maxMessageUploads positive count
     * @return this builder
     */
    public Builder withMaxMessageUploads(int maxMessageUploads) {
      this.maxMessageUploads = maxMessageUploads;
      return this;
    }

    /**
     * Set the most message and interrupt requests one principal has read and queued at a time.
     *
     * @param maxMessageUploadsPerPrincipal positive count
     * @return this builder
     */
    public Builder withMaxMessageUploadsPerPrincipal(int maxMessageUploadsPerPrincipal) {
      this.maxMessageUploadsPerPrincipal = maxMessageUploadsPerPrincipal;
      return this;
    }

    /**
     * Set the most event streams open at a time across the service.
     *
     * @param maxEventStreams positive count
     * @return this builder
     */
    public Builder withMaxEventStreams(int maxEventStreams) {
      this.maxEventStreams = maxEventStreams;
      return this;
    }

    /**
     * Set the most event streams one principal has open at a time.
     *
     * @param maxEventStreamsPerPrincipal positive count
     * @return this builder
     */
    public Builder withMaxEventStreamsPerPrincipal(int maxEventStreamsPerPrincipal) {
      this.maxEventStreamsPerPrincipal = maxEventStreamsPerPrincipal;
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
     * Set the most result polls in progress at a time across the service.
     *
     * @param maxResultPolls positive count
     * @return this builder
     */
    public Builder withMaxResultPolls(int maxResultPolls) {
      this.maxResultPolls = maxResultPolls;
      return this;
    }

    /**
     * Set the most result polls one principal has in progress at a time.
     *
     * @param maxResultPollsPerPrincipal positive count
     * @return this builder
     */
    public Builder withMaxResultPollsPerPrincipal(int maxResultPollsPerPrincipal) {
      this.maxResultPollsPerPrincipal = maxResultPollsPerPrincipal;
      return this;
    }

    /**
     * Set the most result polls in progress on one session at a time.
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
          maxRequestBytes,
          maxMessageUploads,
          maxMessageUploadsPerPrincipal,
          maxEventStreams,
          maxEventStreamsPerPrincipal,
          maxEventStreamsPerSession,
          maxResultPolls,
          maxResultPollsPerPrincipal,
          maxResultPollsPerSession);
    }
  }
}
