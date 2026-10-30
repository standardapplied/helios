/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.common.Validate;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * The ceilings the agent loop enforces.
 *
 * <p>{@code maxTurns}, {@code maxBudgetMicroUsd} and {@code maxWallClock} apply to each run of the
 * session: every run starts at turn 1, with no spend and a fresh wall clock. Every other limit
 * applies per call, per turn or per session, and {@code stopGrace} bounds both a run's end and the
 * session's close.
 *
 * <p>Nine orthogonal knobs — turns, dollars, wall-clock, per-tool timeout, stop grace, context
 * tokens, stream-retry policy, start timeout, tool result size — each mapping to a distinct
 * termination path, recovery, shutdown or history behaviour. Limits are immutable for the lifetime
 * of a session; runtime mutation would let a long-running session escape the contract its caller
 * saw at construction.
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
 *   <li>{@code stopGrace}: 5 seconds — how long anything the session cancels is given to stop:
 *       <ul>
 *         <li>a tool call, once timed out, cancelled and interrupted: one that stops in time is
 *             {@link com.standardapplied.helios.core.tool.ToolOutcome.TimedOut}, one that does not
 *             {@link com.standardapplied.helios.core.tool.ToolOutcome.Unknown}, and until it ends
 *             every later tool call of the session is rejected;
 *         <li>a run's end: its tool calls still running, cancelled first, are waited for this long,
 *             then logged as such, and the run's result settles; such a tool's effects may land
 *             after it has;
 *         <li>the whole of the session's close, whose waits share one deadline: for the run in
 *             progress to end; for the tool calls still running to end; for the execution
 *             provider's {@code onSessionEnd} to return. A tool call or {@code onSessionEnd} still
 *             running at the deadline is logged as such and the close moves on, and a step after
 *             one that used the deadline up is not waited for;
 *         <li>a model stream's reader, once the stream is closed: one still reading then is logged
 *             as such and the turn ends without it.
 *       </ul>
 *   <li>{@code maxContextTokens}: {@code 0} — sentinel meaning "auto, resolve from {@code
 *       Model.contextWindow()}". The agent loop picks an effective ceiling by reading the
 *       provider's documented window and subtracting a small output-token reservation; library
 *       users override with an explicit cap via {@link Builder#withMaxContextTokens(long)} when
 *       they want to compact earlier (or use a tighter cost ceiling). Any positive value is treated
 *       as a hard cap and clamped against the model's actual window to avoid requesting more
 *       context than the provider supports.
 *   <li>{@code streamRetryPolicy}: 3 attempts, 1 s/4 s/16 s exponential back-off capped at 30 s,
 *       ±25% jitter (see {@link StreamRetryPolicy#defaults()}). The loop retries a turn that failed
 *       with {@link com.standardapplied.helios.core.model.TransientStreamException}: a transport
 *       failure, a 408, 429 or 5xx answer to the stream request, or an error event the provider
 *       marks retryable mid-stream. It waits the answer's {@code Retry-After} capped by the
 *       back-off's maximum delay when the answer carried one, the back-off's delay otherwise. On
 *       exhaustion the run ends as {@link ResultMessage.ErrorTransientStream}. Use {@link
 *       StreamRetryPolicy#disabled()} for fail-fast behaviour.
 *   <li>{@code startTimeout}: 60 seconds — how long {@link AgentSession#open} waits for the
 *       execution provider's {@code onSessionStart}, which may fork a sandbox. A start that has not
 *       returned by then, like one the provider refuses, makes {@code open} throw {@link
 *       SessionUnavailableException}; an acceptance that comes later is ended with {@code
 *       onSessionEnd}.
 *   <li>{@code maxToolResultChars}: 65,536 — about 16k tokens at four characters per token, and
 *       above the 50,000-byte stream cap of a bounded process, so one full stream is never cut. A
 *       longer result enters history as its start, a marker stating how much was cut, and its end;
 *       events, hooks and traces keep the whole redacted output.
 * </ul>
 *
 * <p>Currency is integer micro-USD (Stripe-style fixed-precision). See {@link CostEstimate} for the
 * rationale. Use {@link CostEstimate#MICRO_USD_PER_USD} to convert between full dollars and
 * micro-USD when wiring a budget.
 *
 * @param maxTurns hard ceiling on a run's turns: each run runs at most this many turns, and ends in
 *     {@link ResultMessage.ErrorMaxTurns} when the last of them would continue; must be positive
 * @param maxBudgetMicroUsd optional spend ceiling in micro-USD; if present, must be strictly
 *     positive
 * @param maxWallClock wall-clock ceiling from a run's start to its terminal state; must be non-null
 *     and strictly positive
 * @param toolTimeoutDefault default per-tool execution timeout; must be non-null and strictly
 *     positive
 * @param stopGrace how long anything the session cancels is given to stop; must be non-null and
 *     strictly positive
 * @param maxContextTokens soft trigger for context compaction, measured as the {@link
 *     com.standardapplied.helios.core.context.TokenCounter}'s estimate of the <em>total tokens
 *     across every message in the conversation history so far</em> — cumulative, never per-turn.
 *     Must be non-negative. {@code 0} signals "auto" — the agent loop resolves an effective ceiling
 *     from {@link com.standardapplied.helios.core.model.Model#contextWindow()}. Any positive value
 *     is an explicit cap clamped against the model's actual window
 * @param streamRetryPolicy how the loop retries a turn that failed with {@link
 *     com.standardapplied.helios.core.model.TransientStreamException}; non-null
 * @param startTimeout how long {@code onSessionStart} is waited for; must be non-null and strictly
 *     positive
 * @param maxToolResultChars the most characters of one tool result's text that enter history; at
 *     least 1,024
 */
public record SessionLimits(
    int maxTurns,
    OptionalLong maxBudgetMicroUsd,
    Duration maxWallClock,
    Duration toolTimeoutDefault,
    Duration stopGrace,
    long maxContextTokens,
    StreamRetryPolicy streamRetryPolicy,
    Duration startTimeout,
    int maxToolResultChars) {

  private static final Duration MAX_DURATION = Duration.ofNanos(Long.MAX_VALUE);
  private static final int MIN_TOOL_RESULT_CHARS = 1_024;

  private static final SessionLimits DEFAULTS =
      new SessionLimits(
          100,
          OptionalLong.empty(),
          Duration.ofHours(1),
          Duration.ofMinutes(2),
          Duration.ofSeconds(5),
          0L,
          StreamRetryPolicy.defaults(),
          Duration.ofSeconds(60),
          65_536);

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
    requirePositiveNanos("stopGrace", stopGrace);
    if (maxContextTokens < 0) {
      throw new IllegalArgumentException(
          "maxContextTokens must be non-negative, got " + maxContextTokens);
    }
    Objects.requireNonNull(streamRetryPolicy, "streamRetryPolicy must not be null");
    requirePositiveNanos("startTimeout", startTimeout);
    if (maxToolResultChars < MIN_TOOL_RESULT_CHARS) {
      throw new IllegalArgumentException(
          "maxToolResultChars must be at least "
              + MIN_TOOL_RESULT_CHARS
              + ", got "
              + maxToolResultChars);
    }
  }

  private static void requirePositiveNanos(String name, Duration value) {
    if (Validate.positiveDuration(name, value).compareTo(MAX_DURATION) > 0) {
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
        .withStopGrace(stopGrace)
        .withMaxContextTokens(maxContextTokens)
        .withStreamRetryPolicy(streamRetryPolicy)
        .withStartTimeout(startTimeout)
        .withMaxToolResultChars(maxToolResultChars);
  }

  /** Mutable builder for {@link SessionLimits}. */
  public static final class Builder {

    private int maxTurns = DEFAULTS.maxTurns;
    private OptionalLong maxBudgetMicroUsd = DEFAULTS.maxBudgetMicroUsd;
    private Duration maxWallClock = DEFAULTS.maxWallClock;
    private Duration toolTimeoutDefault = DEFAULTS.toolTimeoutDefault;
    private Duration stopGrace = DEFAULTS.stopGrace;
    private long maxContextTokens = DEFAULTS.maxContextTokens;
    private StreamRetryPolicy streamRetryPolicy = DEFAULTS.streamRetryPolicy;
    private Duration startTimeout = DEFAULTS.startTimeout;
    private int maxToolResultChars = DEFAULTS.maxToolResultChars;

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

    /** Set how long anything the session cancels is given to stop. */
    public Builder withStopGrace(Duration stopGrace) {
      this.stopGrace = stopGrace;
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
     * Set how long {@link AgentSession#open} waits for the execution provider's {@code
     * onSessionStart} before it throws {@link SessionUnavailableException}.
     *
     * @param startTimeout strictly positive
     * @return this builder
     */
    public Builder withStartTimeout(Duration startTimeout) {
      this.startTimeout = startTimeout;
      return this;
    }

    /**
     * Set the most characters of one tool result's text that enter history. A longer result enters
     * history as its start, a marker stating how much was cut, and its end.
     *
     * @param maxToolResultChars at least 1,024
     * @return this builder
     */
    public Builder withMaxToolResultChars(int maxToolResultChars) {
      this.maxToolResultChars = maxToolResultChars;
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
          stopGrace,
          maxContextTokens,
          streamRetryPolicy,
          startTimeout,
          maxToolResultChars);
    }
  }
}
