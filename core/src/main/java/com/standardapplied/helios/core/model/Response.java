/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

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

  /**
   * Metadata key under which a provider reports the policy area behind a {@link
   * FinishReason#REFUSAL} or {@link FinishReason#CONTENT_FILTER} (e.g. Anthropic's {@code
   * stop_details.category}: {@code cyber}, {@code bio}). An open set — treat unknown values as
   * opaque. Absent when the provider names no category.
   */
  public static final String REFUSAL_CATEGORY_KEY = "refusal.category";

  /**
   * Metadata key under which a provider reports its human-readable explanation of a {@link
   * FinishReason#REFUSAL} or {@link FinishReason#CONTENT_FILTER}. Display text, not a stable
   * identifier. Absent when the provider gives none.
   */
  public static final String REFUSAL_EXPLANATION_KEY = "refusal.explanation";

  /**
   * Metadata key under which a provider reports the id of the model that served the response, as
   * the provider spelled it: it may be a dated snapshot of the requested id, or another model.
   * Absent when the provider reports none; never filled from the requested model.
   */
  public static final String RESPONSE_MODEL_KEY = "response.model";

  /**
   * Starts a builder for an unstructured response, one whose {@link #parsed()} stays {@code null}.
   *
   * @return a new builder with every list and map empty and every other component {@code null}
   */
  public static Builder<Void> newBuilder() {
    return new Builder<>();
  }

  /**
   * Starts a builder for a structured response whose parsed value is a {@code T}.
   *
   * @param <T> the type of the parsed structured output
   * @param type the parsed value's class; it only fixes {@code T} and is not otherwise read, so it
   *     may be null
   * @return a new builder with every list and map empty and every other component {@code null}
   */
  public static <T> Builder<T> newBuilder(Class<T> type) {
    return new Builder<>();
  }

  /**
   * Whether the model asked for at least one tool call, making this an intermediate turn.
   *
   * @return {@code true} if {@link #toolCalls()} is non-null and non-empty
   */
  public boolean hasToolCalls() {
    return toolCalls != null && !toolCalls.isEmpty();
  }

  /**
   * Whether the response carries reasoning text.
   *
   * @return {@code true} if {@link #thinking()} is non-null and non-empty
   */
  public boolean hasThinking() {
    return thinking != null && !thinking.isEmpty();
  }

  /**
   * Whether the response cites at least one source.
   *
   * @return {@code true} if {@link #citations()} is non-null and non-empty
   */
  public boolean hasCitations() {
    return citations != null && !citations.isEmpty();
  }

  /**
   * Whether a structured output was parsed from the response.
   *
   * @return {@code true} if {@link #parsed()} is non-null
   */
  public boolean hasParsed() {
    return parsed != null;
  }

  /**
   * Turns the response into the assistant message that records it in a conversation: its content,
   * tool calls and metadata, so a provider's round-trip data travels with the turn. Parsed value,
   * usage, thinking text and citations are not carried.
   *
   * @return a {@link Role#ASSISTANT} message
   * @throws NullPointerException if {@link #toolCalls()} is null or holds a null element
   */
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
   * com.standardapplied.helios.core.common.CostCalculator.Pricing} apply a distinct rate per class
   * without double-counting.
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
   * @param promptTokens the size of the whole prompt of the last request this usage covers: input,
   *     cache-creation and cache-read tokens are disjoint, so for one request their sum is every
   *     token it sent, for every provider. Usage added over several requests, such as the segments
   *     of an Anthropic {@code pause_turn}, keeps the last one's, never their total
   */
  public record Usage(
      int inputTokens,
      int outputTokens,
      int cacheCreationInputTokens,
      int cacheReadInputTokens,
      int totalTokens,
      long promptTokens) {
    /**
     * Canonical constructor.
     *
     * @throws IllegalArgumentException if a count is negative, {@code totalTokens} is not the sum
     *     of the four counts, or {@code promptTokens} is negative or greater than the sum of the
     *     three input counts
     * @throws ArithmeticException if the sum of the four counts overflows an {@code int}
     */
    public Usage {
      if (inputTokens < 0
          || outputTokens < 0
          || cacheCreationInputTokens < 0
          || cacheReadInputTokens < 0) {
        throw new IllegalArgumentException(
            "token counts must be non-negative, got "
                + List.of(
                    inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens));
      }
      var sum = sum(inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens);
      if (totalTokens != sum) {
        throw new IllegalArgumentException(
            "totalTokens must be the sum of the four counts, " + sum + ", got " + totalTokens);
      }
      var inputs = inputs(inputTokens, cacheCreationInputTokens, cacheReadInputTokens);
      if (promptTokens < 0 || promptTokens > inputs) {
        throw new IllegalArgumentException(
            "promptTokens must lie in [0, " + inputs + "], got " + promptTokens);
      }
    }

    /**
     * Build a {@link Usage} from input and output token counts. Cache counts default to zero — use
     * {@link #of(int, int, int, int)} when the provider reported prompt-caching activity.
     *
     * @param input prompt tokens
     * @param output completion tokens
     * @return a usage record with cache fields zero and {@code totalTokens = input + output}
     */
    public static Usage of(int input, int output) {
      return of(input, output, 0, 0);
    }

    /**
     * Add a later request's usage to this one, count by count; the total is recomputed from the
     * summed counts. {@link #promptTokens} is the later request's size, or this one's when the
     * later reports none, so adding a zero usage, such as that of a call cut before its provider
     * reported any, changes nothing.
     *
     * @param other the usage of a later request; non-null
     * @return a usage record with every count summed and the last reported prompt size
     * @throws ArithmeticException on overflow of any count or of the total
     */
    public Usage plus(Usage other) {
      Objects.requireNonNull(other, "other must not be null");
      var input = Math.addExact(inputTokens, other.inputTokens);
      var output = Math.addExact(outputTokens, other.outputTokens);
      var cacheCreation = Math.addExact(cacheCreationInputTokens, other.cacheCreationInputTokens);
      var cacheRead = Math.addExact(cacheReadInputTokens, other.cacheReadInputTokens);
      return new Usage(
          input,
          output,
          cacheCreation,
          cacheRead,
          sum(input, output, cacheCreation, cacheRead),
          other.promptTokens > 0 ? other.promptTokens : promptTokens);
    }

    /**
     * Whether any of the four billable token counts is positive.
     *
     * @return {@code true} if input, output, cache-creation or cache-read tokens are positive
     */
    public boolean reportsTokens() {
      return totalTokens > 0;
    }

    /**
     * Build a {@link Usage} including prompt-cache token counts.
     *
     * @param input uncached prompt tokens
     * @param output completion tokens
     * @param cacheCreation tokens written to the provider's prompt cache
     * @param cacheRead tokens read from the provider's prompt cache
     * @return a usage record of one request, with {@code totalTokens} summing all four counts and
     *     {@code promptTokens} the three input counts
     * @throws IllegalArgumentException if a count is negative
     * @throws ArithmeticException if the sum of the four counts overflows an {@code int}
     */
    public static Usage of(int input, int output, int cacheCreation, int cacheRead) {
      return new Usage(
          input,
          output,
          cacheCreation,
          cacheRead,
          sum(input, output, cacheCreation, cacheRead),
          inputs(input, cacheCreation, cacheRead));
    }

    private static int sum(int input, int output, int cacheCreation, int cacheRead) {
      return Math.addExact(Math.addExact(input, output), Math.addExact(cacheCreation, cacheRead));
    }

    private static long inputs(int input, int cacheCreation, int cacheRead) {
      return (long) input + cacheCreation + cacheRead;
    }
  }

  /**
   * Builds a {@link Response}. The {@code with*} methods for lists and maps copy their argument and
   * treat {@code null} as empty; every other setting is kept as given and nothing is validated. Not
   * thread-safe.
   *
   * @param <T> the type of the parsed structured output
   */
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

    /**
     * Sets the response's text. {@code null} by default, as for a turn that only calls tools.
     *
     * @param content the text; may be null
     * @return this builder
     */
    public Builder<T> withContent(String content) {
      this.content = content;
      return this;
    }

    /**
     * Sets the structured output parsed from the text. {@code null} by default, meaning nothing was
     * parsed.
     *
     * @param parsed the parsed value; may be null
     * @return this builder
     */
    public Builder<T> withParsed(T parsed) {
      this.parsed = parsed;
      return this;
    }

    /**
     * Sets the tool calls the model requested. Empty by default.
     *
     * @param toolCalls the calls, copied; {@code null} is treated as empty
     * @return this builder
     * @throws NullPointerException if {@code toolCalls} holds a null element
     */
    public Builder<T> withToolCalls(List<ToolCall> toolCalls) {
      this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
      return this;
    }

    /**
     * Sets why the model stopped generating. {@code null} by default; a reader such as {@link
     * ModelChunk.MessageStop#of(Response)} takes an unset reason as {@link FinishReason#STOP}.
     *
     * @param finishReason the finish reason; may be null
     * @return this builder
     */
    public Builder<T> withFinishReason(FinishReason finishReason) {
      this.finishReason = finishReason;
      return this;
    }

    /**
     * Sets the tokens the call consumed. {@code null} by default, meaning the provider reported
     * none.
     *
     * @param usage the usage; may be null
     * @return this builder
     */
    public Builder<T> withUsage(Usage usage) {
      this.usage = usage;
      return this;
    }

    /**
     * Sets the reasoning text the model returned. {@code null} by default, meaning it returned
     * none.
     *
     * @param thinking the reasoning text; may be null
     * @return this builder
     */
    public Builder<T> withThinking(String thinking) {
      this.thinking = thinking;
      return this;
    }

    /**
     * Sets the sources the response cites. Empty by default.
     *
     * @param citations the citations, copied; {@code null} is treated as empty
     * @return this builder
     * @throws NullPointerException if {@code citations} holds a null element
     */
    public Builder<T> withCitations(List<Citation> citations) {
      this.citations = citations != null ? List.copyOf(citations) : List.of();
      return this;
    }

    /**
     * Sets the provider-specific round-trip data, such as thinking signatures or the {@link
     * #REFUSAL_CATEGORY_KEY refusal keys}. Empty by default.
     *
     * @param metadata the metadata, copied; {@code null} is treated as empty
     * @return this builder
     * @throws NullPointerException if {@code metadata} holds a null key or value
     */
    public Builder<T> withMetadata(Map<String, String> metadata) {
      this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
      return this;
    }

    /**
     * Builds the response from the settings as they stand; nothing is validated.
     *
     * @return the response
     */
    public Response<T> build() {
      return new Response<>(
          content, parsed, toolCalls, finishReason, usage, thinking, citations, metadata);
    }
  }
}
