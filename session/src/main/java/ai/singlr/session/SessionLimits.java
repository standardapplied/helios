/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.common.CostEstimate;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Per-session ceilings the agent loop enforces.
 *
 * <p>Six orthogonal limits — turns, dollars, wall-clock, per-tool timeout, context tokens,
 * stream-idle timeout — each mapping to a distinct termination path in {@link ResultMessage}.
 * Limits are immutable for the lifetime of a session; runtime mutation would let a long-running
 * session escape the contract its caller saw at construction.
 *
 * <p>Defaults track Helios production values:
 *
 * <ul>
 *   <li>{@code maxTurns}: 100 — covers any practical multi-step task without sponsoring runaway
 *       loops.
 *   <li>{@code maxBudgetMicroUsd}: unset — opt in only; production deployments typically enforce
 *       budget in the control plane rather than the SDK.
 *   <li>{@code maxWallClock}: 1 hour — every long-running task we've shipped finished well inside
 *       this window.
 *   <li>{@code toolTimeoutDefault}: 2 minutes — slow enough for compile-and-run flows, fast enough
 *       that a wedged tool doesn't burn the wall-clock budget.
 *   <li>{@code maxContextTokens}: 180_000 — soft trigger for compaction; the loop reads this to
 *       decide when to compact, not to hard-fail.
 *   <li>{@code streamIdleTimeout}: 60 seconds — if a provider stream emits no chunk for this long,
 *       the turn fails with {@link ai.singlr.core.model.FinishReason#ERROR}, surfaced as {@link
 *       ResultMessage.ErrorDuringExecution} with a {@code stream-idle-timeout} error code. Guards
 *       against silent-socket / hung-edge stalls that {@code maxWallClock} would otherwise catch
 *       only at the end of the wall-clock budget. Composes with future model-call retry — a turn
 *       that idle-times-out is a natural retry candidate.
 * </ul>
 *
 * <p>Currency is integer micro-USD (Stripe-style fixed-precision). See {@link CostEstimate} for the
 * rationale. Use {@link CostEstimate#MICRO_USD_PER_USD} to convert between full dollars and
 * micro-USD when wiring a budget.
 *
 * @param maxTurns hard ceiling on agent-loop iterations; must be positive
 * @param maxBudgetMicroUsd optional spend ceiling in micro-USD; if present, must be strictly
 *     positive
 * @param maxWallClock wall-clock ceiling from session start to terminal state; must be non-null and
 *     strictly positive
 * @param toolTimeoutDefault default per-tool execution timeout; must be non-null and strictly
 *     positive
 * @param maxContextTokens soft trigger for context compaction in tokens; must be positive
 * @param streamIdleTimeout per-chunk idle ceiling on a model stream; must be non-null and strictly
 *     positive
 */
public record SessionLimits(
    int maxTurns,
    OptionalLong maxBudgetMicroUsd,
    Duration maxWallClock,
    Duration toolTimeoutDefault,
    long maxContextTokens,
    Duration streamIdleTimeout) {

  private static final SessionLimits DEFAULTS =
      new SessionLimits(
          100,
          OptionalLong.empty(),
          Duration.ofHours(1),
          Duration.ofMinutes(2),
          180_000L,
          Duration.ofSeconds(60));

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code maxBudgetMicroUsd}, {@code maxWallClock}, or {@code
   *     toolTimeoutDefault} is null
   * @throws IllegalArgumentException if any numeric value violates its positivity contract
   */
  public SessionLimits {
    if (maxTurns <= 0) {
      throw new IllegalArgumentException("maxTurns must be positive, got " + maxTurns);
    }
    Objects.requireNonNull(maxBudgetMicroUsd, "maxBudgetMicroUsd must not be null");
    if (maxBudgetMicroUsd.isPresent() && maxBudgetMicroUsd.getAsLong() <= 0L) {
      throw new IllegalArgumentException(
          "maxBudgetMicroUsd must be positive when present, got " + maxBudgetMicroUsd.getAsLong());
    }
    Objects.requireNonNull(maxWallClock, "maxWallClock must not be null");
    if (maxWallClock.isZero() || maxWallClock.isNegative()) {
      throw new IllegalArgumentException(
          "maxWallClock must be strictly positive, got " + maxWallClock);
    }
    Objects.requireNonNull(toolTimeoutDefault, "toolTimeoutDefault must not be null");
    if (toolTimeoutDefault.isZero() || toolTimeoutDefault.isNegative()) {
      throw new IllegalArgumentException(
          "toolTimeoutDefault must be strictly positive, got " + toolTimeoutDefault);
    }
    if (maxContextTokens <= 0) {
      throw new IllegalArgumentException(
          "maxContextTokens must be positive, got " + maxContextTokens);
    }
    Objects.requireNonNull(streamIdleTimeout, "streamIdleTimeout must not be null");
    if (streamIdleTimeout.isZero() || streamIdleTimeout.isNegative()) {
      throw new IllegalArgumentException(
          "streamIdleTimeout must be strictly positive, got " + streamIdleTimeout);
    }
  }

  /**
   * Returns the shared default-limits singleton.
   *
   * @return the production defaults documented in the class Javadoc
   */
  public static SessionLimits defaults() {
    return DEFAULTS;
  }

  /**
   * Start building a {@code SessionLimits}. The builder starts at {@link #defaults()}; each {@code
   * with*} setter overrides one field.
   *
   * @return a fresh builder seeded with defaults
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Start a builder seeded with this instance's values. Convenience for callers that want to derive
   * a variant.
   *
   * @return a builder pre-populated from this record
   */
  public Builder toBuilder() {
    return new Builder()
        .withMaxTurns(maxTurns)
        .withMaxBudgetMicroUsd(maxBudgetMicroUsd)
        .withMaxWallClock(maxWallClock)
        .withToolTimeoutDefault(toolTimeoutDefault)
        .withMaxContextTokens(maxContextTokens)
        .withStreamIdleTimeout(streamIdleTimeout);
  }

  /** Mutable builder for {@link SessionLimits}. */
  public static final class Builder {

    private int maxTurns = DEFAULTS.maxTurns;
    private OptionalLong maxBudgetMicroUsd = DEFAULTS.maxBudgetMicroUsd;
    private Duration maxWallClock = DEFAULTS.maxWallClock;
    private Duration toolTimeoutDefault = DEFAULTS.toolTimeoutDefault;
    private long maxContextTokens = DEFAULTS.maxContextTokens;
    private Duration streamIdleTimeout = DEFAULTS.streamIdleTimeout;

    private Builder() {}

    /**
     * Set the maximum number of agent-loop iterations.
     *
     * @param maxTurns positive
     * @return this builder
     */
    public Builder withMaxTurns(int maxTurns) {
      this.maxTurns = maxTurns;
      return this;
    }

    /**
     * Set the budget ceiling in micro-USD. Use {@link CostEstimate#MICRO_USD_PER_USD} to convert
     * from full dollars: {@code 5 * CostEstimate.MICRO_USD_PER_USD} for a $5 cap.
     *
     * @param maxBudgetMicroUsd positive micro-USD ceiling
     * @return this builder
     */
    public Builder withMaxBudgetMicroUsd(long maxBudgetMicroUsd) {
      this.maxBudgetMicroUsd = OptionalLong.of(maxBudgetMicroUsd);
      return this;
    }

    /**
     * Set or clear the budget ceiling via an {@code OptionalLong}. Useful for {@link #toBuilder()}
     * round-trips.
     *
     * @param maxBudgetMicroUsd non-null optional
     * @return this builder
     * @throws NullPointerException if {@code maxBudgetMicroUsd} is null
     */
    public Builder withMaxBudgetMicroUsd(OptionalLong maxBudgetMicroUsd) {
      this.maxBudgetMicroUsd =
          Objects.requireNonNull(maxBudgetMicroUsd, "maxBudgetMicroUsd must not be null");
      return this;
    }

    /** Clear any previously-set budget. */
    public Builder withoutMaxBudget() {
      this.maxBudgetMicroUsd = OptionalLong.empty();
      return this;
    }

    /** Set the wall-clock ceiling. */
    public Builder withMaxWallClock(Duration maxWallClock) {
      this.maxWallClock = maxWallClock;
      return this;
    }

    /** Set the default per-tool timeout. */
    public Builder withToolTimeoutDefault(Duration toolTimeoutDefault) {
      this.toolTimeoutDefault = toolTimeoutDefault;
      return this;
    }

    /** Set the soft context-compaction trigger in tokens. */
    public Builder withMaxContextTokens(long maxContextTokens) {
      this.maxContextTokens = maxContextTokens;
      return this;
    }

    /**
     * Set the per-chunk stream-idle ceiling. A model stream that emits no chunk within this
     * duration is treated as stalled and the turn fails with {@link
     * ai.singlr.core.model.FinishReason#ERROR}.
     */
    public Builder withStreamIdleTimeout(Duration streamIdleTimeout) {
      this.streamIdleTimeout = streamIdleTimeout;
      return this;
    }

    /**
     * Build the immutable record.
     *
     * @return the limits
     */
    public SessionLimits build() {
      return new SessionLimits(
          maxTurns,
          maxBudgetMicroUsd,
          maxWallClock,
          toolTimeoutDefault,
          maxContextTokens,
          streamIdleTimeout);
    }
  }
}
