/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.CostEstimate;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Per-session ceilings the agent loop enforces.
 *
 * <p>Ten orthogonal knobs — turns, dollars, wall-clock, per-tool timeout, tool stop grace, context
 * tokens, stream-idle timeout, stream-retry policy, start timeout, teardown grace — each mapping to
 * a distinct termination path, recovery or shutdown behaviour. Limits are immutable for the
 * lifetime of a session; runtime mutation would let a long-running session escape the contract its
 * caller saw at construction.
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
 *   <li>{@code toolStopGrace}: 5 seconds — how long the loop waits, once a tool call has timed out
 *       and been cancelled and interrupted, for the tool to stop. A tool that stops in time is
 *       reported as {@link com.standardapplied.helios.core.tool.ToolOutcome.TimedOut}; one that
 *       does not as {@link com.standardapplied.helios.core.tool.ToolOutcome.Unknown}, and it keeps
 *       its category's capacity until it ends.
 *   <li>{@code maxContextTokens}: {@code 0} — sentinel meaning "auto, resolve from {@code
 *       Model.contextWindow()}". The agent loop picks an effective ceiling by reading the
 *       provider's documented window and subtracting a small output-token reservation; library
 *       users override with an explicit cap via {@link Builder#withMaxContextTokens(long)} when
 *       they want to compact earlier (or use a tighter cost ceiling). Any positive value is treated
 *       as a hard cap and clamped against the model's actual window to avoid requesting more
 *       context than the provider supports.
 *   <li>{@code streamIdleTimeout}: 60 seconds — once a model stream has published its first chunk,
 *       if it publishes none for this long the turn fails with {@link
 *       com.standardapplied.helios.core.model.FinishReason#ERROR} and the stream is cancelled,
 *       surfaced as {@link ResultMessage.ErrorDuringExecution} carrying the {@link
 *       java.util.concurrent.TimeoutException}; the turn is not retried. Every chunk re-arms it,
 *       including a {@link com.standardapplied.helios.core.model.ModelChunk.KeepAlive}, which a
 *       provider publishes for each frame that carries no content. The clock does not run before
 *       the first chunk: a provider's first frame arrives with its response headers, which a model
 *       may hold back while it thinks, and that wait is bounded by {@code
 *       ModelConfig.responseTimeout} (default 60 seconds, a retryable failure), {@code
 *       maxWallClock} and cancellation. A {@link
 *       com.standardapplied.helios.core.model.BlockingModel} publishes nothing until it answers, so
 *       the clock does not run during its call. The defaults never race: once the stream is open
 *       this clock always fires before the provider's per-line {@code
 *       ModelConfig.streamIdleTimeout} (default 120 seconds), which guards direct {@code chat} /
 *       {@code chatStream} callers. Guards against silent-socket / hung-edge stalls that {@code
 *       maxWallClock} would otherwise catch only at the end of the wall-clock budget.
 *   <li>{@code streamRetryPolicy}: 3 attempts, 1 s/4 s/16 s exponential back-off capped at 30 s,
 *       ±25% jitter (see {@link StreamRetryPolicy#defaults()}). The loop retries a turn that failed
 *       with {@link com.standardapplied.helios.core.model.TransientStreamException}; on exhaustion
 *       the session terminates as {@link ResultMessage.ErrorTransientStream}. Use {@link
 *       StreamRetryPolicy#disabled()} for fail-fast behaviour.
 *   <li>{@code startTimeout}: 60 seconds — how long {@link AgentSession#create} waits for the
 *       execution provider's {@code onSessionStart}, which may fork a sandbox. A start that has not
 *       returned by then, like one the provider refuses, ends the session with {@link
 *       ResultMessage.ErrorProviderUnavailable}; an acceptance that comes later is ended with
 *       {@code onSessionEnd}.
 *   <li>{@code teardownGrace}: 5 seconds — how long each wait of the session's teardown lasts: for
 *       the tool calls still running, cancelled first, to end; for the execution provider's {@code
 *       onSessionEnd} to return; for event subscribers to drain. A tool call or {@code
 *       onSessionEnd} still running when its wait ends is logged as such and the teardown moves on,
 *       so the result settles; such a tool's effects may land after it has.
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
 * @param toolStopGrace how long a timed-out tool call is given to stop before it is reported as
 *     {@link com.standardapplied.helios.core.tool.ToolOutcome.Unknown}; must be non-null and
 *     strictly positive
 * @param maxContextTokens soft trigger for context compaction, measured as the {@link
 *     com.standardapplied.helios.core.context.TokenCounter}'s estimate of the <em>total tokens
 *     across every message in the conversation history so far</em> — cumulative, never per-turn.
 *     Must be non-negative. {@code 0} signals "auto" — the agent loop resolves an effective ceiling
 *     from {@link com.standardapplied.helios.core.model.Model#contextWindow()}. Any positive value
 *     is an explicit cap clamped against the model's actual window
 * @param streamIdleTimeout per-chunk idle ceiling on a model stream, from its first chunk; must be
 *     non-null and strictly positive
 * @param streamRetryPolicy how the loop retries a turn that failed with {@link
 *     com.standardapplied.helios.core.model.TransientStreamException}; non-null
 * @param startTimeout how long {@code onSessionStart} is waited for; must be non-null and strictly
 *     positive
 * @param teardownGrace how long each wait of the session's teardown lasts; must be non-null and
 *     strictly positive
 */
public record SessionLimits(
    int maxTurns,
    OptionalLong maxBudgetMicroUsd,
    Duration maxWallClock,
    Duration toolTimeoutDefault,
    Duration toolStopGrace,
    long maxContextTokens,
    Duration streamIdleTimeout,
    StreamRetryPolicy streamRetryPolicy,
    Duration startTimeout,
    Duration teardownGrace) {

  private static final Duration MAX_DURATION = Duration.ofNanos(Long.MAX_VALUE);

  private static final SessionLimits DEFAULTS =
      new SessionLimits(
          100,
          OptionalLong.empty(),
          Duration.ofHours(1),
          Duration.ofMinutes(2),
          Duration.ofSeconds(5),
          0L,
          Duration.ofSeconds(60),
          StreamRetryPolicy.defaults(),
          Duration.ofSeconds(60),
          Duration.ofSeconds(5));

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any non-numeric argument is null
   * @throws IllegalArgumentException if any numeric value violates its positivity contract, or a
   *     duration exceeds {@code Long.MAX_VALUE} nanoseconds (about 292 years)
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
    requirePositiveNanos("maxWallClock", maxWallClock);
    requirePositiveNanos("toolTimeoutDefault", toolTimeoutDefault);
    requirePositiveNanos("toolStopGrace", toolStopGrace);
    if (maxContextTokens < 0) {
      throw new IllegalArgumentException(
          "maxContextTokens must be non-negative, got " + maxContextTokens);
    }
    requirePositiveNanos("streamIdleTimeout", streamIdleTimeout);
    Objects.requireNonNull(streamRetryPolicy, "streamRetryPolicy must not be null");
    requirePositiveNanos("startTimeout", startTimeout);
    requirePositiveNanos("teardownGrace", teardownGrace);
  }

  private static void requirePositiveNanos(String name, Duration value) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be strictly positive, got " + value);
    }
    if (value.compareTo(MAX_DURATION) > 0) {
      throw new IllegalArgumentException(
          name + " must be at most " + MAX_DURATION + " (Long.MAX_VALUE ns), got " + value);
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
        .withToolStopGrace(toolStopGrace)
        .withMaxContextTokens(maxContextTokens)
        .withStreamIdleTimeout(streamIdleTimeout)
        .withStreamRetryPolicy(streamRetryPolicy)
        .withStartTimeout(startTimeout)
        .withTeardownGrace(teardownGrace);
  }

  /** Mutable builder for {@link SessionLimits}. */
  public static final class Builder {

    private int maxTurns = DEFAULTS.maxTurns;
    private OptionalLong maxBudgetMicroUsd = DEFAULTS.maxBudgetMicroUsd;
    private Duration maxWallClock = DEFAULTS.maxWallClock;
    private Duration toolTimeoutDefault = DEFAULTS.toolTimeoutDefault;
    private Duration toolStopGrace = DEFAULTS.toolStopGrace;
    private long maxContextTokens = DEFAULTS.maxContextTokens;
    private Duration streamIdleTimeout = DEFAULTS.streamIdleTimeout;
    private StreamRetryPolicy streamRetryPolicy = DEFAULTS.streamRetryPolicy;
    private Duration startTimeout = DEFAULTS.startTimeout;
    private Duration teardownGrace = DEFAULTS.teardownGrace;

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

    /**
     * Set how long a timed-out tool call is given to stop before it is reported as {@link
     * com.standardapplied.helios.core.tool.ToolOutcome.Unknown}.
     *
     * @param toolStopGrace strictly positive
     * @return this builder
     */
    public Builder withToolStopGrace(Duration toolStopGrace) {
      this.toolStopGrace = toolStopGrace;
      return this;
    }

    /**
     * Set the soft context-compaction trigger in tokens.
     *
     * <p>{@code 0} signals "auto" — the agent loop derives an effective ceiling from {@link
     * com.standardapplied.helios.core.model.Model#contextWindow()} at run time, subtracting a small
     * output-token reservation for the next response. Any positive value is an explicit cap and the
     * loop uses the smaller of the cap and the model's actual window.
     *
     * @param maxContextTokens non-negative token ceiling; {@code 0} means auto
     * @return this builder
     */
    public Builder withMaxContextTokens(long maxContextTokens) {
      this.maxContextTokens = maxContextTokens;
      return this;
    }

    /**
     * Set the per-chunk stream-idle ceiling. A model stream that, after its first chunk, emits no
     * chunk within this duration is treated as stalled and the turn fails with {@link
     * com.standardapplied.helios.core.model.FinishReason#ERROR}. Keep it below the model's {@code
     * ModelConfig.streamIdleTimeout} so the session's clock fires first.
     */
    public Builder withStreamIdleTimeout(Duration streamIdleTimeout) {
      this.streamIdleTimeout = streamIdleTimeout;
      return this;
    }

    /**
     * Set the stream-retry policy. The loop applies this policy when a turn fails with {@link
     * com.standardapplied.helios.core.model.TransientStreamException}. Pass {@link
     * StreamRetryPolicy#disabled()} to opt out of retry entirely; the loop will then terminate on
     * the first transient failure via {@link ResultMessage.ErrorTransientStream}.
     *
     * @param streamRetryPolicy the policy; non-null
     * @return this builder
     */
    public Builder withStreamRetryPolicy(StreamRetryPolicy streamRetryPolicy) {
      this.streamRetryPolicy = streamRetryPolicy;
      return this;
    }

    /**
     * Set how long {@link AgentSession#create} waits for the execution provider's {@code
     * onSessionStart} before it ends the session with {@link
     * ResultMessage.ErrorProviderUnavailable}.
     *
     * @param startTimeout strictly positive
     * @return this builder
     */
    public Builder withStartTimeout(Duration startTimeout) {
      this.startTimeout = startTimeout;
      return this;
    }

    /**
     * Set how long each wait of the session's teardown lasts: for the tool calls still running to
     * end, for the execution provider's {@code onSessionEnd} to return, and for event subscribers
     * to drain.
     *
     * @param teardownGrace strictly positive
     * @return this builder
     */
    public Builder withTeardownGrace(Duration teardownGrace) {
      this.teardownGrace = teardownGrace;
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
          toolStopGrace,
          maxContextTokens,
          streamIdleTimeout,
          streamRetryPolicy,
          startTimeout,
          teardownGrace);
    }
  }
}
