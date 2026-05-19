/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import ai.singlr.core.common.Strings;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for model providers.
 *
 * <p>Contains provider-agnostic settings like API credentials, HTTP timeouts, and generation
 * parameters.
 *
 * @param apiKey the API key for authentication
 * @param thinkingLevel level of reasoning trace to include in responses
 * @param connectTimeout maximum time to establish HTTP connection
 * @param responseTimeout maximum time to wait for HTTP response headers
 * @param temperature controls randomness (0.0 = deterministic, 2.0 = very random)
 * @param topP nucleus sampling threshold (0.0-1.0)
 * @param maxOutputTokens maximum tokens to generate
 * @param stopSequences sequences that stop generation
 * @param seed random seed for reproducibility
 * @param toolChoice controls how the model uses tools
 * @param googleSearch whether to enable Google Search grounding
 * @param urlContext whether to enable URL context (fetches web content from URLs in messages)
 * @param streamIdleTimeout maximum time to wait for next SSE data line during streaming. Default
 *     120s — high enough for reasoning models that pause for tens of seconds during extended
 *     thinking before emitting tokens. Override via the builder for chat-only workloads where
 *     faster network-failure detection matters more than tolerating long pauses.
 * @param baseUrl override the provider's default API endpoint. {@code null} (default) means use the
 *     provider's canonical URL ({@code https://api.openai.com/v1/responses}, {@code
 *     https://api.anthropic.com/v1/messages}, {@code
 *     https://generativelanguage.googleapis.com/v1beta}). Set to an Azure OpenAI deployment URL, an
 *     OpenAI-compatible proxy (LiteLLM, vLLM, Ollama), Vertex AI, or Bedrock to point a provider at
 *     a non-default endpoint. The framework does not parse or interpret this value — it is used
 *     verbatim as the request URI's authority + path prefix
 * @param headers extra HTTP headers added to every request. Empty by default. Names match
 *     case-insensitively against the provider's built-in headers ({@code Authorization}, {@code
 *     x-api-key}, {@code x-goog-api-key}, etc.) and user-supplied values replace built-in values of
 *     the same name. Common use: set {@code api-key} when pointing {@link #baseUrl} at Azure OpenAI
 *     (which uses {@code api-key} rather than {@code Authorization: Bearer})
 */
public record ModelConfig(
    String apiKey,
    ThinkingLevel thinkingLevel,
    Duration connectTimeout,
    Duration responseTimeout,
    Double temperature,
    Double topP,
    Integer maxOutputTokens,
    List<String> stopSequences,
    Long seed,
    ToolChoice toolChoice,
    boolean googleSearch,
    boolean urlContext,
    Duration streamIdleTimeout,
    String baseUrl,
    Map<String, String> headers) {

  public ModelConfig {
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  /**
   * Renders the config without leaking secret material. The {@code apiKey} component and every
   * {@code headers} value are replaced with {@code <redacted>} — header names are preserved (useful
   * for diagnostics) but values are not, since they routinely carry bearer tokens, Azure api-key
   * entries, or other auth material. Java's default record {@code toString} would print every
   * component verbatim and silently land secrets in stack traces, exception messages, and any
   * logger.info("config={}", cfg) callsite.
   */
  @Override
  public String toString() {
    var sb = new StringBuilder("ModelConfig[");
    sb.append("apiKey=").append(apiKey == null ? "null" : "<redacted>");
    sb.append(", thinkingLevel=").append(thinkingLevel);
    sb.append(", connectTimeout=").append(connectTimeout);
    sb.append(", responseTimeout=").append(responseTimeout);
    sb.append(", temperature=").append(temperature);
    sb.append(", topP=").append(topP);
    sb.append(", maxOutputTokens=").append(maxOutputTokens);
    sb.append(", stopSequences=").append(stopSequences);
    sb.append(", seed=").append(seed);
    sb.append(", toolChoice=").append(toolChoice);
    sb.append(", googleSearch=").append(googleSearch);
    sb.append(", urlContext=").append(urlContext);
    sb.append(", streamIdleTimeout=").append(streamIdleTimeout);
    sb.append(", baseUrl=").append(baseUrl);
    sb.append(", headers={");
    var first = true;
    for (var name : headers.keySet()) {
      if (!first) {
        sb.append(", ");
      }
      sb.append(name).append("=<redacted>");
      first = false;
    }
    sb.append("}]");
    return sb.toString();
  }

  /**
   * Resolve the effective base URL for a provider HTTP request. Returns the configured {@link
   * #baseUrl()} when set (non-null, non-blank); otherwise falls back to the provider's built-in
   * default. Used by {@code OpenAIModel}, {@code AnthropicModel}, {@code GeminiModel} to choose
   * between {@code https://api.openai.com/v1/responses} (or peer) and a user-supplied override.
   */
  public String effectiveBaseUrl(String providerDefault) {
    return !Strings.isBlank(baseUrl) ? baseUrl : providerDefault;
  }

  /**
   * Resolve the effective HTTP header map for a provider request. Starts from {@code defaults} and
   * applies {@link #headers()} with case-insensitive override semantics — a user header whose name
   * case-insensitively matches a default replaces the default value entirely. Insertion order of
   * the result is the {@code defaults} order followed by any user-only names. Result is immutable.
   */
  public Map<String, String> effectiveHeaders(Map<String, String> defaults) {
    var merged = new LinkedHashMap<String, String>();
    if (defaults != null) {
      merged.putAll(defaults);
    }
    if (headers != null) {
      for (var userEntry : headers.entrySet()) {
        var userName = userEntry.getKey();
        String existingKey = null;
        for (var key : merged.keySet()) {
          if (key.equalsIgnoreCase(userName)) {
            existingKey = key;
            break;
          }
        }
        if (existingKey != null) {
          merged.remove(existingKey);
        }
        merged.put(userName, userEntry.getValue());
      }
    }
    return Collections.unmodifiableMap(merged);
  }

  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(120);

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ModelConfig config) {
    return new Builder(config);
  }

  public static ModelConfig of(String apiKey) {
    return new Builder().withApiKey(apiKey).build();
  }

  public static class Builder {
    private String apiKey;
    private ThinkingLevel thinkingLevel = ThinkingLevel.NONE;
    private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private Duration responseTimeout = DEFAULT_RESPONSE_TIMEOUT;
    private Double temperature;
    private Double topP;
    private Integer maxOutputTokens;
    private List<String> stopSequences;
    private Long seed;
    private ToolChoice toolChoice;
    private boolean googleSearch;
    private boolean urlContext;
    private Duration streamIdleTimeout = DEFAULT_STREAM_IDLE_TIMEOUT;
    private String baseUrl;
    private LinkedHashMap<String, String> headers = new LinkedHashMap<>();

    private Builder() {}

    private Builder(ModelConfig config) {
      this.apiKey = config.apiKey;
      this.thinkingLevel = config.thinkingLevel;
      this.connectTimeout = config.connectTimeout;
      this.responseTimeout = config.responseTimeout;
      this.temperature = config.temperature;
      this.topP = config.topP;
      this.maxOutputTokens = config.maxOutputTokens;
      this.stopSequences = config.stopSequences;
      this.seed = config.seed;
      this.toolChoice = config.toolChoice;
      this.googleSearch = config.googleSearch;
      this.urlContext = config.urlContext;
      this.streamIdleTimeout = config.streamIdleTimeout;
      this.baseUrl = config.baseUrl;
      this.headers = new LinkedHashMap<>(config.headers);
    }

    public Builder withApiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public Builder withThinkingLevel(ThinkingLevel thinkingLevel) {
      this.thinkingLevel = thinkingLevel;
      return this;
    }

    public Builder withConnectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout;
      return this;
    }

    public Builder withResponseTimeout(Duration responseTimeout) {
      this.responseTimeout = responseTimeout;
      return this;
    }

    public Builder withTemperature(Double temperature) {
      this.temperature = temperature;
      return this;
    }

    public Builder withTopP(Double topP) {
      this.topP = topP;
      return this;
    }

    public Builder withMaxOutputTokens(Integer maxOutputTokens) {
      this.maxOutputTokens = maxOutputTokens;
      return this;
    }

    public Builder withStopSequences(List<String> stopSequences) {
      this.stopSequences = stopSequences;
      return this;
    }

    public Builder withSeed(Long seed) {
      this.seed = seed;
      return this;
    }

    public Builder withToolChoice(ToolChoice toolChoice) {
      this.toolChoice = toolChoice;
      return this;
    }

    public Builder withGoogleSearch(boolean googleSearch) {
      this.googleSearch = googleSearch;
      return this;
    }

    public Builder withUrlContext(boolean urlContext) {
      this.urlContext = urlContext;
      return this;
    }

    public Builder withStreamIdleTimeout(Duration streamIdleTimeout) {
      this.streamIdleTimeout = streamIdleTimeout;
      return this;
    }

    /**
     * Override the provider's default API endpoint. See {@link ModelConfig#baseUrl()} for the
     * canonical URLs each provider falls back to when this is {@code null}.
     */
    public Builder withBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /**
     * Replace the current header map with the given entries. {@code null} clears all extra headers.
     * Names match case-insensitively against built-in provider headers and override them.
     */
    public Builder withHeaders(Map<String, String> headers) {
      this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
      return this;
    }

    /**
     * Add or replace a single extra HTTP header. Case-insensitive override against built-in
     * provider headers (e.g. setting {@code api-key} replaces the Azure-incompatible default {@code
     * Authorization} on the OpenAI provider). Repeated calls with the same name overwrite.
     */
    public Builder withHeader(String name, String value) {
      if (Strings.isBlank(name)) {
        throw new IllegalArgumentException("header name is required");
      }
      this.headers.put(name, value);
      return this;
    }

    public ModelConfig build() {
      return new ModelConfig(
          apiKey,
          thinkingLevel,
          connectTimeout,
          responseTimeout,
          temperature,
          topP,
          maxOutputTokens,
          stopSequences,
          seed,
          toolChoice,
          googleSearch,
          urlContext,
          streamIdleTimeout,
          baseUrl,
          Map.copyOf(headers));
    }
  }
}
