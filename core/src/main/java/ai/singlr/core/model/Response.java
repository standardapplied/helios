/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import java.util.List;
import java.util.Map;

/**
 * Response from the model.
 *
 * @param <T> the type of parsed structured output (Void for unstructured responses)
 * @param content the text content of the response
 * @param parsed the parsed structured output (null for unstructured responses)
 * @param toolCalls tool calls requested by the model
 * @param finishReason why the model stopped generating
 * @param usage token usage statistics (optional)
 * @param thinking reasoning trace from extended thinking models (optional)
 * @param citations source citations for RAG responses (optional)
 * @param metadata provider-specific data for round-tripping (e.g., thought signatures)
 */
public record Response<T>(
    String content,
    T parsed,
    List<ToolCall> toolCalls,
    FinishReason finishReason,
    Usage usage,
    String thinking,
    List<Citation> citations,
    Map<String, String> metadata) {

  public static Builder<Void> newBuilder() {
    return new Builder<>();
  }

  public static <T> Builder<T> newBuilder(Class<T> type) {
    return new Builder<>();
  }

  public boolean hasToolCalls() {
    return toolCalls != null && !toolCalls.isEmpty();
  }

  public boolean hasThinking() {
    return thinking != null && !thinking.isEmpty();
  }

  public boolean hasCitations() {
    return citations != null && !citations.isEmpty();
  }

  public boolean hasParsed() {
    return parsed != null;
  }

  public Message toMessage() {
    return Message.assistant(content, toolCalls, metadata);
  }

  /**
   * Token usage statistics for a single model call.
   *
   * <h2>Canonical Helios semantics — every token is in exactly one class</h2>
   *
   * The four counts ({@code inputTokens}, {@code outputTokens}, {@code cacheCreationInputTokens},
   * {@code cacheReadInputTokens}) are <b>disjoint</b>: a token contributes to exactly one of them.
   * Providers translate their wire shape into this canonical form, which lets {@link
   * ai.singlr.core.common.CostCalculator.Pricing} apply a distinct rate per class without
   * double-counting.
   *
   * <ul>
   *   <li><b>Anthropic native shape</b> already matches: {@code input_tokens} is uncached, {@code
   *       cache_creation_input_tokens} and {@code cache_read_input_tokens} are separate.
   *   <li><b>OpenAI native shape</b> reports {@code input_tokens} as the <i>total</i> with {@code
   *       input_tokens_details.cached_tokens} as a <i>subset</i>. The OpenAI provider subtracts on
   *       the way in so the Helios shape stays disjoint. OpenAI does not premium cache writes, so
   *       {@code cacheCreationInputTokens} is always zero from that provider.
   *   <li><b>Providers without cache reporting</b> leave the cache fields at zero — existing {@code
   *       Usage.of(input, output)} call sites need no change.
   * </ul>
   *
   * <p>{@link #totalTokens} sums every billable token across all four classes, so dashboards and
   * traces still have a single "what did I spend" handle even though sub-counts bill at different
   * rates.
   *
   * @param inputTokens uncached prompt tokens billed at the base input rate
   * @param outputTokens completion tokens billed at the base output rate
   * @param cacheCreationInputTokens tokens written to the provider's prompt cache; typically billed
   *     at a small premium over the base input rate (Anthropic: 1.25× for 5m TTL, 2.0× for 1h TTL)
   * @param cacheReadInputTokens tokens read from the provider's prompt cache; typically billed at a
   *     deep discount (Anthropic: 0.10×; OpenAI: ~0.50× depending on model)
   * @param totalTokens convenience sum {@code input + output + cacheCreation + cacheRead}
   */
  public record Usage(
      int inputTokens,
      int outputTokens,
      int cacheCreationInputTokens,
      int cacheReadInputTokens,
      int totalTokens) {
    /**
     * Build a {@link Usage} from input and output token counts. Cache counts default to zero — use
     * {@link #of(int, int, int, int)} when the provider reported prompt-caching activity.
     *
     * @param input prompt tokens
     * @param output completion tokens
     * @return a usage record with cache fields zero and {@code totalTokens = input + output}
     */
    public static Usage of(int input, int output) {
      return new Usage(input, output, 0, 0, input + output);
    }

    /**
     * Build a {@link Usage} including prompt-cache token counts.
     *
     * @param input uncached prompt tokens
     * @param output completion tokens
     * @param cacheCreation tokens written to the provider's prompt cache
     * @param cacheRead tokens read from the provider's prompt cache
     * @return a usage record with {@code totalTokens} summing all four counts
     */
    public static Usage of(int input, int output, int cacheCreation, int cacheRead) {
      var total =
          Math.addExact(Math.addExact(input, output), Math.addExact(cacheCreation, cacheRead));
      return new Usage(input, output, cacheCreation, cacheRead, total);
    }
  }

  public static class Builder<T> {
    private String content;
    private T parsed;
    private List<ToolCall> toolCalls = List.of();
    private FinishReason finishReason;
    private Usage usage;
    private String thinking;
    private List<Citation> citations = List.of();
    private Map<String, String> metadata = Map.of();

    private Builder() {}

    public Builder<T> withContent(String content) {
      this.content = content;
      return this;
    }

    public Builder<T> withParsed(T parsed) {
      this.parsed = parsed;
      return this;
    }

    public Builder<T> withToolCalls(List<ToolCall> toolCalls) {
      this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
      return this;
    }

    public Builder<T> withFinishReason(FinishReason finishReason) {
      this.finishReason = finishReason;
      return this;
    }

    public Builder<T> withUsage(Usage usage) {
      this.usage = usage;
      return this;
    }

    public Builder<T> withThinking(String thinking) {
      this.thinking = thinking;
      return this;
    }

    public Builder<T> withCitations(List<Citation> citations) {
      this.citations = citations != null ? List.copyOf(citations) : List.of();
      return this;
    }

    public Builder<T> withMetadata(Map<String, String> metadata) {
      this.metadata = metadata != null ? metadata : Map.of();
      return this;
    }

    public Response<T> build() {
      return new Response<>(
          content, parsed, toolCalls, finishReason, usage, thinking, citations, metadata);
    }
  }
}
