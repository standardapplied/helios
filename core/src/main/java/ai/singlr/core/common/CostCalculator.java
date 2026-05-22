/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.common;

import ai.singlr.core.model.Response.Usage;
import java.util.Map;
import java.util.Objects;

/**
 * Per-model {@code Usage → CostEstimate} lookup. Plug a calculator into a session and every
 * completed model turn contributes to the session's accumulated cost; combined with {@code
 * SessionLimits.maxBudgetMicroUsd} the loop terminates when the budget is exceeded.
 *
 * <p>The framework deliberately ships no rate cards. Public model prices change too often for a
 * library release cadence to keep up with, and a stale baked-in table is worse than {@link #ZERO}
 * since it silently under-bills. Compose a {@link #staticTable(Map)} at application startup from
 * whatever pricing source the deployer trusts (vendor docs, billing API, internal catalog) and
 * refresh it on the schedule that matches the deployer's reconciliation cycle.
 *
 * <h2>Currency representation</h2>
 *
 * Rates and amounts are integer micro-USD (1 microUSD = $10⁻⁶) following the Stripe-style
 * fixed-precision pattern. See {@link CostEstimate} for the rationale.
 */
@FunctionalInterface
public interface CostCalculator {

  /**
   * No-op calculator that always returns {@link CostEstimate#zero()}. The default for sessions that
   * have not wired a calculator — cost tracking is opt-in.
   */
  CostCalculator ZERO = (modelId, usage) -> CostEstimate.zero();

  /**
   * Compute the cost of a single model turn.
   *
   * @param modelId the {@code Model.id()} that produced the turn; non-null
   * @param usage the token usage reported by the turn; non-null
   * @return the cost contribution as a non-negative {@link CostEstimate}
   */
  CostEstimate cost(String modelId, Usage usage);

  /**
   * Build a calculator from a fixed pricing table keyed by {@code Model.id()}. Models absent from
   * the table contribute {@link CostEstimate#zero()} — there is no fallback rate, since a missing
   * entry is more likely a configuration gap than something the framework should guess at.
   *
   * <p>The table is defensively copied; later mutations to {@code pricing} do not affect the
   * returned calculator.
   *
   * @param pricing model id → pricing; non-null, may be empty
   * @return a {@code CostCalculator} backed by the snapshot
   * @throws NullPointerException if {@code pricing} is null or contains a null key/value
   */
  static CostCalculator staticTable(Map<String, Pricing> pricing) {
    Objects.requireNonNull(pricing, "pricing must not be null");
    var snapshot = Map.copyOf(pricing);
    return (modelId, usage) -> {
      Objects.requireNonNull(modelId, "modelId must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
      var rate = snapshot.get(modelId);
      return rate == null ? CostEstimate.zero() : rate.cost(usage);
    };
  }

  /**
   * Per-million-token rates for a single model, split across four billable token classes. Rates are
   * expressed in micro-USD per million tokens; storing per-million matches the unit every public
   * rate card today publishes (e.g., "$15 per million input tokens") and keeps {@link #cost(Usage)}
   * as an integer multiply-then-divide.
   *
   * <p>Prompt caching, where supported by the provider, bills the four token classes at distinct
   * rates: uncached input at {@code inputMicroUsdPerMillion}, output at {@code
   * outputMicroUsdPerMillion}, cache writes at {@code cacheWriteMicroUsdPerMillion} (typically a
   * small premium over base input), and cache reads at {@code cacheReadMicroUsdPerMillion}
   * (typically a deep discount). Providers that do not report cache token counts always leave the
   * cache fields at zero in {@link Usage}, so deployers without a caching story can keep using
   * {@link #ofUsdPerMillion(double, double)} and pay only for input + output as before.
   *
   * @param inputMicroUsdPerMillion micro-USD per million uncached input tokens; non-negative
   * @param outputMicroUsdPerMillion micro-USD per million output tokens; non-negative
   * @param cacheWriteMicroUsdPerMillion micro-USD per million cache-write input tokens;
   *     non-negative
   * @param cacheReadMicroUsdPerMillion micro-USD per million cache-read input tokens; non-negative
   */
  record Pricing(
      long inputMicroUsdPerMillion,
      long outputMicroUsdPerMillion,
      long cacheWriteMicroUsdPerMillion,
      long cacheReadMicroUsdPerMillion) {

    private static final long TOKENS_PER_MILLION = 1_000_000L;

    /** Anthropic's published premium ratio for prompt-cache writes against base input. */
    public static final double ANTHROPIC_CACHE_WRITE_MULTIPLIER = 1.25d;

    /** Anthropic's published discount ratio for prompt-cache reads against base input. */
    public static final double ANTHROPIC_CACHE_READ_MULTIPLIER = 0.10d;

    /**
     * Canonical constructor.
     *
     * @throws IllegalArgumentException if any rate is negative
     */
    public Pricing {
      if (inputMicroUsdPerMillion < 0L) {
        throw new IllegalArgumentException(
            "inputMicroUsdPerMillion must be non-negative, got " + inputMicroUsdPerMillion);
      }
      if (outputMicroUsdPerMillion < 0L) {
        throw new IllegalArgumentException(
            "outputMicroUsdPerMillion must be non-negative, got " + outputMicroUsdPerMillion);
      }
      if (cacheWriteMicroUsdPerMillion < 0L) {
        throw new IllegalArgumentException(
            "cacheWriteMicroUsdPerMillion must be non-negative, got "
                + cacheWriteMicroUsdPerMillion);
      }
      if (cacheReadMicroUsdPerMillion < 0L) {
        throw new IllegalArgumentException(
            "cacheReadMicroUsdPerMillion must be non-negative, got " + cacheReadMicroUsdPerMillion);
      }
    }

    /**
     * Two-arg convenience constructor: no explicit cache rates. Cache-write and cache-read rates
     * default to the base input rate, so deployers without a caching story compute exactly as
     * before. The provider may still report cache token counts; they'll be billed at the base input
     * rate rather than ignored, which keeps the math conservative — caching cannot make billing
     * surface a lower number than the no-cache run reported.
     *
     * @param inputMicroUsdPerMillion micro-USD per million input tokens; non-negative
     * @param outputMicroUsdPerMillion micro-USD per million output tokens; non-negative
     */
    public Pricing(long inputMicroUsdPerMillion, long outputMicroUsdPerMillion) {
      this(
          inputMicroUsdPerMillion,
          outputMicroUsdPerMillion,
          inputMicroUsdPerMillion,
          inputMicroUsdPerMillion);
    }

    /**
     * Convenience for rates expressed in dollars-per-million-tokens with no explicit cache rates.
     * Tests and scripts; production callers should prefer the canonical constructor with raw
     * micro-USD to avoid the floating-point conversion. Fractional micro-USD round half-up.
     *
     * @param inputUsdPerMillion USD per million input tokens; non-negative
     * @param outputUsdPerMillion USD per million output tokens; non-negative
     * @return a {@code Pricing} with cache rates defaulting to the input rate
     */
    public static Pricing ofUsdPerMillion(double inputUsdPerMillion, double outputUsdPerMillion) {
      var inputMicro = Math.round(inputUsdPerMillion * CostEstimate.MICRO_USD_PER_USD);
      var outputMicro = Math.round(outputUsdPerMillion * CostEstimate.MICRO_USD_PER_USD);
      return new Pricing(inputMicro, outputMicro, inputMicro, inputMicro);
    }

    /**
     * Convenience for rates expressed in dollars-per-million-tokens with explicit cache rates.
     *
     * @param inputUsdPerMillion USD per million uncached input tokens; non-negative
     * @param outputUsdPerMillion USD per million output tokens; non-negative
     * @param cacheWriteUsdPerMillion USD per million cache-write tokens; non-negative
     * @param cacheReadUsdPerMillion USD per million cache-read tokens; non-negative
     * @return a {@code Pricing}
     */
    public static Pricing ofUsdPerMillion(
        double inputUsdPerMillion,
        double outputUsdPerMillion,
        double cacheWriteUsdPerMillion,
        double cacheReadUsdPerMillion) {
      return new Pricing(
          Math.round(inputUsdPerMillion * CostEstimate.MICRO_USD_PER_USD),
          Math.round(outputUsdPerMillion * CostEstimate.MICRO_USD_PER_USD),
          Math.round(cacheWriteUsdPerMillion * CostEstimate.MICRO_USD_PER_USD),
          Math.round(cacheReadUsdPerMillion * CostEstimate.MICRO_USD_PER_USD));
    }

    /**
     * Build a {@code Pricing} using Anthropic's published prompt-cache ratios — cache writes at
     * {@value #ANTHROPIC_CACHE_WRITE_MULTIPLIER}× base input, cache reads at {@value
     * #ANTHROPIC_CACHE_READ_MULTIPLIER}× base input. Use for any Claude model whose rate card
     * matches the published ratios; deployers paying a different ratio (volume discounts, custom
     * enterprise pricing) should build a {@link Pricing} explicitly with the four-arg factory.
     *
     * @param inputUsdPerMillion USD per million uncached input tokens; non-negative
     * @param outputUsdPerMillion USD per million output tokens; non-negative
     * @return a {@code Pricing} with cache rates derived from {@code inputUsdPerMillion}
     */
    public static Pricing anthropicCaching(double inputUsdPerMillion, double outputUsdPerMillion) {
      return ofUsdPerMillion(
          inputUsdPerMillion,
          outputUsdPerMillion,
          inputUsdPerMillion * ANTHROPIC_CACHE_WRITE_MULTIPLIER,
          inputUsdPerMillion * ANTHROPIC_CACHE_READ_MULTIPLIER);
    }

    /**
     * Compute the cost of one turn's usage at this pricing. Integer math: {@code (tokens × rate) /
     * 1_000_000} per class, summed. Sub-microUSD per-token amounts truncate to zero, which is exact
     * for typical turn-sized aggregates and meaningless at the per-token margin.
     *
     * @param usage the token usage; non-null
     * @return the cost as a non-negative {@link CostEstimate}
     * @throws ArithmeticException on overflow (rate × tokens exceeds {@link Long#MAX_VALUE})
     */
    public CostEstimate cost(Usage usage) {
      Objects.requireNonNull(usage, "usage must not be null");
      var inputMicro =
          Math.multiplyExact((long) usage.inputTokens(), inputMicroUsdPerMillion)
              / TOKENS_PER_MILLION;
      var outputMicro =
          Math.multiplyExact((long) usage.outputTokens(), outputMicroUsdPerMillion)
              / TOKENS_PER_MILLION;
      var cacheWriteMicro =
          Math.multiplyExact((long) usage.cacheCreationInputTokens(), cacheWriteMicroUsdPerMillion)
              / TOKENS_PER_MILLION;
      var cacheReadMicro =
          Math.multiplyExact((long) usage.cacheReadInputTokens(), cacheReadMicroUsdPerMillion)
              / TOKENS_PER_MILLION;
      return CostEstimate.ofMicroUsd(
          Math.addExact(
              Math.addExact(inputMicro, outputMicro),
              Math.addExact(cacheWriteMicro, cacheReadMicro)));
    }
  }
}
