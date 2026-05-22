/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token usage statistics from the Interactions API ({@code Api-Revision: 2026-05-20}).
 *
 * <p>The live wire shape carries {@code total_tokens}, {@code total_input_tokens}, {@code
 * total_output_tokens}, {@code total_cached_tokens} (plus optional per-modality breakdowns and
 * {@code total_thought_tokens} that we don't decode yet). The breaking-change migration doc reads
 * {@code prompt_tokens}/{@code completion_tokens}, but the deployed v2 server still emits the
 * {@code total_*} family. We track the wire reality.
 *
 * <h2>Prompt caching on Gemini 2.5+</h2>
 *
 * Gemini 2.5+ models enable <b>implicit caching</b> automatically on the Interactions API — no
 * request annotation needed. When a request shares a prefix with a prior request (system
 * instruction + early contents) within Google's cache window, the server bills the cached portion
 * at the discounted rate and reports the count via {@code total_cached_tokens}. Explicit caching
 * (the {@code CachedContent} CRUD API) is <i>not</i> supported by the Interactions API surface —
 * only by the legacy {@code generateContent} endpoint, which Helios does not use.
 *
 * <p>On the wire, {@code total_input_tokens} is the <i>total</i> prompt-side count and {@code
 * total_cached_tokens} is the <i>subset</i> served from cache. The {@link
 * ai.singlr.gemini.GeminiModel} provider re-projects this into the disjoint {@link
 * ai.singlr.core.model.Response.Usage} shape so cost accounting never double-counts cached tokens
 * at the base input rate.
 *
 * @param totalTokens total tokens used
 * @param inputTokens prompt / input tokens (wire field {@code total_input_tokens}) — TOTAL,
 *     inclusive of any cached subset
 * @param outputTokens completion / output tokens (wire field {@code total_output_tokens})
 * @param cachedTokens portion of {@code totalInputTokens} served from Gemini's implicit prompt
 *     cache (wire field {@code total_cached_tokens}). May be {@code null} on responses that
 *     pre-date implicit caching (Gemini 1.5/2.0 models) or on proxies that strip the field
 */
public record InteractionUsage(
    @JsonProperty("total_tokens") Integer totalTokens,
    @JsonProperty("total_input_tokens") Integer inputTokens,
    @JsonProperty("total_output_tokens") Integer outputTokens,
    @JsonProperty("total_cached_tokens") Integer cachedTokens) {

  /**
   * Pre-cache-aware constructor used by tests and any caller that only cares about the top-level
   * counts. Leaves {@link #cachedTokens()} as {@code null} so {@link #cachedTokensOrZero()} returns
   * zero.
   *
   * @param totalTokens total tokens
   * @param inputTokens prompt tokens
   * @param outputTokens output tokens
   */
  public InteractionUsage(Integer totalTokens, Integer inputTokens, Integer outputTokens) {
    this(totalTokens, inputTokens, outputTokens, null);
  }

  /**
   * Return the cached-tokens count or zero when the field is absent. Gemini 1.5 / 2.0 responses
   * omit the field entirely; Gemini 2.5+ always emits it (zero when no cache hit occurred). The
   * accessor normalizes both shapes to a single {@code int}.
   *
   * @return tokens served from cache; non-negative
   */
  public int cachedTokensOrZero() {
    return cachedTokens == null ? 0 : cachedTokens;
  }
}
