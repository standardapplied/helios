/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token usage statistics from the OpenAI Responses API.
 *
 * <p>OpenAI's prompt-caching billing reports the cached portion of a prompt under {@link
 * #inputTokensDetails()} (path: {@code usage.input_tokens_details.cached_tokens}). On the wire
 * {@link #inputTokens} is the <b>total</b> prompt tokens (cached + uncached); the cached count is a
 * subset. The Helios provider re-projects this into the disjoint {@link
 * ai.singlr.core.model.Response.Usage} shape so downstream cost accounting is unambiguous.
 *
 * <p>OpenAI does not premium cache writes (writes bill at the base input rate); only cache reads
 * carry a discount. So no {@code cache_creation} field is reported, and the Helios {@code
 * cacheCreationInputTokens} stays zero for this provider.
 *
 * @param inputTokens TOTAL number of prompt tokens (cached + uncached)
 * @param outputTokens number of output tokens generated
 * @param totalTokens total tokens (input + output) — provider-side sum
 * @param inputTokensDetails per-class breakdown of {@code input_tokens}; carries the cached subset
 * @param outputTokensDetails per-class breakdown of {@code output_tokens}; carries reasoning subset
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiUsage(
    @JsonProperty("input_tokens") Integer inputTokens,
    @JsonProperty("output_tokens") Integer outputTokens,
    @JsonProperty("total_tokens") Integer totalTokens,
    @JsonProperty("input_tokens_details") InputTokensDetails inputTokensDetails,
    @JsonProperty("output_tokens_details") OutputTokensDetails outputTokensDetails) {

  /**
   * Pre-cache-aware ctor used by tests and any caller that only cares about the top-level counts.
   *
   * @param inputTokens total prompt tokens
   * @param outputTokens output tokens
   * @param totalTokens sum
   */
  public ApiUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {
    this(inputTokens, outputTokens, totalTokens, null, null);
  }

  /**
   * Return the cached-tokens count or zero when the details object is absent. OpenAI populates
   * {@code input_tokens_details.cached_tokens=0} for prompts below the 1024-token caching threshold
   * rather than omitting the field, but earlier API versions and some proxies do omit it — the
   * accessor normalizes both shapes to a single {@code int}.
   *
   * @return tokens served from cache; non-negative
   */
  public int cachedTokensOrZero() {
    if (inputTokensDetails == null || inputTokensDetails.cachedTokens() == null) {
      return 0;
    }
    return inputTokensDetails.cachedTokens();
  }

  /**
   * Return the cache-write-tokens count or zero when absent. OpenAI surfaces {@code
   * cache_write_tokens} on gpt-5.6+ models only (cache writes bill at 1.25× base input there);
   * earlier models and proxies omit it entirely.
   *
   * @return tokens written to the prompt cache; non-negative
   */
  public int cacheWriteTokensOrZero() {
    if (inputTokensDetails == null || inputTokensDetails.cacheWriteTokens() == null) {
      return 0;
    }
    return inputTokensDetails.cacheWriteTokens();
  }

  /**
   * Per-class breakdown of {@link ApiUsage#inputTokens}. OpenAI surfaces {@code cached_tokens}
   * everywhere and {@code cache_write_tokens} on gpt-5.6+; the object is modeled separately so
   * future provider additions (e.g. audio-token breakdowns on multimodal models) don't break
   * consumers.
   *
   * @param cachedTokens portion of {@code input_tokens} served from the prompt cache
   * @param cacheWriteTokens portion of {@code input_tokens} written to the prompt cache (gpt-5.6+;
   *     billed at a write premium there)
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record InputTokensDetails(
      @JsonProperty("cached_tokens") Integer cachedTokens,
      @JsonProperty("cache_write_tokens") Integer cacheWriteTokens) {}

  /**
   * Per-class breakdown of {@link ApiUsage#outputTokens}. OpenAI's Responses API uses this to
   * surface {@code reasoning_tokens} for thinking models (o1, o3, gpt-5-thinking, etc.). Helios
   * already accumulates the reasoning text from streaming events; this field is captured for cost
   * attribution and trace fidelity.
   *
   * @param reasoningTokens portion of {@code output_tokens} spent on chain-of-thought reasoning
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record OutputTokensDetails(@JsonProperty("reasoning_tokens") Integer reasoningTokens) {}
}
