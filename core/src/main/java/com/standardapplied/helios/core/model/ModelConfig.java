/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.model;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Configuration for model providers.
 *
 * <p>Contains provider-agnostic settings like API credentials, HTTP timeouts, and generation
 * parameters.
 *
 * @param apiKey the API key for authentication
 * @param reasoning what the model is asked to do about reasoning; empty sends no reasoning field
 *     and leaves the provider's defaults in force. Never null
 * @param connectTimeout maximum time to establish HTTP connection
 * @param responseTimeout maximum time to wait for HTTP response headers. Default 60s. A model may
 *     think before it sends them (Gemini sends nothing until it has), and while it does this is the
 *     only limit of the provider's that runs, since {@code streamIdleTimeout} starts with the
 *     headers. Expiry fails the turn with a retryable {@link TransientStreamException}
 * @param temperature controls randomness (0.0 = deterministic, 2.0 = very random)
 * @param topP nucleus sampling threshold (0.0-1.0)
 * @param maxOutputTokens maximum tokens to generate
 * @param contextWindow the model's total context-window size in tokens, or {@code null} to defer to
 *     the provider's known value for the model id ({@code 0}/"unknown" for an unrecognized id).
 *     Lets callers declare the window for a model the provider does not yet enumerate, so session
 *     compaction sizes against the real window instead of falling back to a conservative backstop.
 *     Not sent on the wire — purely informational metadata surfaced via {@code
 *     Model.contextWindow()}
 * @param stopSequences sequences that stop generation
 * @param seed random seed for reproducibility
 * @param toolChoice controls how the model uses tools
 * @param webSearch whether to enable provider-native web search (Gemini: Google Search grounding;
 *     Anthropic: the {@code web_search} server tool). Providers or models without web search
 *     support reject this at model construction with {@link IllegalArgumentException}
 * @param webFetch whether to enable provider-native URL fetching (Gemini: URL context; Anthropic:
 *     the {@code web_fetch} server tool). Providers or models without web fetch support reject this
 *     at model construction with {@link IllegalArgumentException}
 * @param promptCacheKey stable cache-routing key sent to providers whose prompt cache benefits from
 *     one (OpenAI {@code prompt_cache_key} — required for improved cache matching on GPT 5.6+).
 *     Choose a key that groups requests sharing a long common prefix (e.g. per tenant or per
 *     agent). Providers without the concept ignore it. Not a secret, but avoid embedding end-user
 *     PII — it travels on every request
 * @param streamIdleTimeout maximum time to wait for the next SSE line once the response headers
 *     have arrived. Default 120s — high enough for reasoning models that pause for tens of seconds
 *     during extended thinking before emitting tokens. It is the one idle clock, for direct callers
 *     and sessions alike: its expiry ends the turn with the provider's exception, which is not
 *     retried. Lower it where faster network-failure detection matters more than tolerating long
 *     pauses.
 * @param baseUrl override the provider's default API endpoint. {@code null} (default) means use the
 *     provider's canonical URL ({@code https://api.openai.com/v1/responses}, {@code
 *     https://api.anthropic.com/v1/messages}, {@code
 *     https://generativelanguage.googleapis.com/v1}). Set to an Azure OpenAI deployment URL, an
 *     OpenAI-compatible proxy (LiteLLM, vLLM, Ollama), Vertex AI, or Bedrock to point a provider at
 *     a non-default endpoint. The framework does not parse or interpret this value — it is used
 *     verbatim as the request URI's authority + path prefix
 * @param headers extra HTTP headers added to every request. Empty by default. Names match
 *     case-insensitively against the provider's built-in headers ({@code Authorization}, {@code
 *     x-api-key}, {@code x-goog-api-key}, etc.) and user-supplied values replace built-in values of
 *     the same name. Common use: set {@code api-key} when pointing {@link #baseUrl} at Azure OpenAI
 *     (which uses {@code api-key} rather than {@code Authorization: Bearer})
 * @param providerContinuation whether providers may use server-side conversation continuation.
 *     Enabled by default, so a provider that supports continuation sends only the new turns and
 *     references the server-held conversation. When disabled, providers that support continuation
 *     resend local message history and must not propagate provider conversation identifiers
 * @param apiVersion provider API version override. {@code null} selects the provider default;
 *     providers that support version selection validate their values at model construction
 * @param rawOutputCapturePolicy whether structured-output exceptions retain raw model responses.
 *     Defaults to {@link RawOutputCapturePolicy#ENABLED}, so the exception carries the raw response
 *     for diagnosis
 */
public record ModelConfig(
    String apiKey,
    Optional<Reasoning> reasoning,
    Duration connectTimeout,
    Duration responseTimeout,
    Double temperature,
    Double topP,
    Integer maxOutputTokens,
    Integer contextWindow,
    List<String> stopSequences,
    Long seed,
    ToolChoice toolChoice,
    boolean webSearch,
    boolean webFetch,
    String promptCacheKey,
    Duration streamIdleTimeout,
    String baseUrl,
    Map<String, String> headers,
    boolean providerContinuation,
    String apiVersion,
    RawOutputCapturePolicy rawOutputCapturePolicy) {

  /**
   * Canonical constructor. A {@code null} {@code reasoning} becomes empty and a {@code null} {@code
   * headers} an empty map; {@code headers} is copied into an unmodifiable map.
   *
   * @throws NullPointerException if {@code rawOutputCapturePolicy} is null, or {@code headers}
   *     holds a null name or value
   * @throws IllegalArgumentException if {@code temperature} or {@code topP} is NaN or infinite
   */
  public ModelConfig {
    reasoning = reasoning == null ? Optional.empty() : reasoning;
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    Objects.requireNonNull(rawOutputCapturePolicy, "rawOutputCapturePolicy must not be null");
    requireFinite("temperature", temperature);
    requireFinite("topP", topP);
  }

  /** A NaN or infinite value would reach the wire as a string no provider accepts. */
  private static void requireFinite(String parameter, Double value) {
    if (value != null && !Double.isFinite(value)) {
      throw new IllegalArgumentException(parameter + " must be a finite number, got " + value);
    }
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
    sb.append(", reasoning=").append(reasoning);
    sb.append(", connectTimeout=").append(connectTimeout);
    sb.append(", responseTimeout=").append(responseTimeout);
    sb.append(", temperature=").append(temperature);
    sb.append(", topP=").append(topP);
    sb.append(", maxOutputTokens=").append(maxOutputTokens);
    sb.append(", contextWindow=").append(contextWindow);
    sb.append(", stopSequences=").append(stopSequences);
    sb.append(", seed=").append(seed);
    sb.append(", toolChoice=").append(toolChoice);
    sb.append(", webSearch=").append(webSearch);
    sb.append(", webFetch=").append(webFetch);
    sb.append(", promptCacheKey=").append(promptCacheKey);
    sb.append(", streamIdleTimeout=").append(streamIdleTimeout);
    sb.append(", baseUrl=").append(baseUrl);
    sb.append(", providerContinuation=").append(providerContinuation);
    sb.append(", apiVersion=").append(apiVersion);
    sb.append(", rawOutputCapturePolicy=").append(rawOutputCapturePolicy);
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
   * default. Each provider uses it to choose between its endpoint, such as {@code
   * https://api.openai.com/v1/responses}, and a user-supplied override.
   *
   * @param providerDefault the provider's canonical endpoint; may be null
   * @return {@link #baseUrl()} when it is not blank, else {@code providerDefault}
   */
  public String effectiveBaseUrl(String providerDefault) {
    return !Strings.isBlank(baseUrl) ? baseUrl : providerDefault;
  }

  /**
   * Resolve the effective HTTP header map for a provider request. Starts from {@code defaults} and
   * applies {@link #headers()} with case-insensitive override semantics — a user header whose name
   * case-insensitively matches a default replaces the default value entirely. Insertion order of
   * the result is the {@code defaults} order followed by any user-only names. Result is immutable.
   *
   * @param defaults the provider's built-in headers; {@code null} is treated as empty
   * @return the merged headers, unmodifiable; empty when both sides are empty
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

  /**
   * Starts a builder holding the defaults: 10 s connect timeout, 60 s response timeout, 120 s
   * stream idle timeout, provider continuation enabled, raw output capture {@link
   * RawOutputCapturePolicy#ENABLED ENABLED}, and every other setting absent or off.
   *
   * @return a new builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Starts a builder holding every setting of {@code config}, so a copy can differ in a few.
   *
   * @param config the configuration to copy; non-null
   * @return a new builder initialised from {@code config}
   * @throws NullPointerException if {@code config} is null
   */
  public static Builder newBuilder(ModelConfig config) {
    return new Builder(config);
  }

  /**
   * Creates a configuration with {@code apiKey} and every other setting at the {@link
   * #newBuilder()} defaults.
   *
   * @param apiKey the provider's API key; a provider refuses a blank key at model construction
   *     unless a {@link #baseUrl()} is set
   * @return the configuration
   */
  public static ModelConfig of(String apiKey) {
    return new Builder().withApiKey(apiKey).build();
  }

  /**
   * Builds a {@link ModelConfig}. Every {@code with*} method replaces the setting it names and,
   * except {@link #withHeader(String, String)}, validates nothing; {@link #build()} validates. Not
   * thread-safe.
   */
  public static class Builder {
    private String apiKey;
    private Reasoning reasoning;
    private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private Duration responseTimeout = DEFAULT_RESPONSE_TIMEOUT;
    private Double temperature;
    private Double topP;
    private Integer maxOutputTokens;
    private Integer contextWindow;
    private List<String> stopSequences;
    private Long seed;
    private ToolChoice toolChoice;
    private boolean webSearch;
    private boolean webFetch;
    private String promptCacheKey;
    private Duration streamIdleTimeout = DEFAULT_STREAM_IDLE_TIMEOUT;
    private String baseUrl;
    private LinkedHashMap<String, String> headers = new LinkedHashMap<>();
    private boolean providerContinuation = true;
    private String apiVersion;
    private RawOutputCapturePolicy rawOutputCapturePolicy = RawOutputCapturePolicy.ENABLED;

    private Builder() {}

    private Builder(ModelConfig config) {
      this.apiKey = config.apiKey;
      this.reasoning = config.reasoning.orElse(null);
      this.connectTimeout = config.connectTimeout;
      this.responseTimeout = config.responseTimeout;
      this.temperature = config.temperature;
      this.topP = config.topP;
      this.maxOutputTokens = config.maxOutputTokens;
      this.contextWindow = config.contextWindow;
      this.stopSequences = config.stopSequences;
      this.seed = config.seed;
      this.toolChoice = config.toolChoice;
      this.webSearch = config.webSearch;
      this.webFetch = config.webFetch;
      this.promptCacheKey = config.promptCacheKey;
      this.streamIdleTimeout = config.streamIdleTimeout;
      this.baseUrl = config.baseUrl;
      this.headers = new LinkedHashMap<>(config.headers);
      this.providerContinuation = config.providerContinuation;
      this.apiVersion = config.apiVersion;
      this.rawOutputCapturePolicy = config.rawOutputCapturePolicy;
    }

    /**
     * Sets the API key the provider sends as its credential header ({@code x-api-key}, {@code
     * x-goog-api-key}, {@code Authorization: Bearer}). Absent by default; a provider refuses a
     * blank key at model construction unless a base URL is set. {@link ModelConfig#toString()}
     * never prints it.
     *
     * @param apiKey the key; may be null
     * @return this builder
     */
    public Builder withApiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Ask the model to reason as {@code reasoning} states; {@code null} (the default) sends no
     * reasoning field. See {@link ModelConfig#reasoning()}.
     *
     * @param reasoning the reasoning request; may be null
     * @return this builder
     */
    public Builder withReasoning(Reasoning reasoning) {
      this.reasoning = reasoning;
      return this;
    }

    /**
     * Sets the longest wait to establish the HTTP connection. Default 10 s; {@code null} leaves the
     * HTTP client factory's own 10 s default in force.
     *
     * @param connectTimeout the connect timeout; may be null
     * @return this builder
     */
    public Builder withConnectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout;
      return this;
    }

    /**
     * Sets the longest wait for the response headers. Default 60 s; {@code null} sets no limit. See
     * {@link ModelConfig#responseTimeout()}.
     *
     * @param responseTimeout the response timeout; may be null
     * @return this builder
     */
    public Builder withResponseTimeout(Duration responseTimeout) {
      this.responseTimeout = responseTimeout;
      return this;
    }

    /**
     * Sets the sampling temperature (0.0 deterministic, up to 2.0 very random). Absent by default,
     * so the provider's default applies. A catalogued model that rejects sampling makes its
     * provider throw at model construction when it is set.
     *
     * @param temperature the temperature; may be null; NaN or infinite fails {@link #build()}
     * @return this builder
     */
    public Builder withTemperature(Double temperature) {
      this.temperature = temperature;
      return this;
    }

    /**
     * Sets the nucleus sampling threshold (0.0 to 1.0). Absent by default, so the provider's
     * default applies. A catalogued model that rejects sampling makes its provider throw at model
     * construction when it is set.
     *
     * @param topP the threshold; may be null; NaN or infinite fails {@link #build()}
     * @return this builder
     */
    public Builder withTopP(Double topP) {
      this.topP = topP;
      return this;
    }

    /**
     * Caps the tokens the model generates per response. {@code null} (default) lets the provider
     * choose: the model's documented ceiling where its catalogue knows the model.
     *
     * @param maxOutputTokens the cap in tokens; may be null
     * @return this builder
     */
    public Builder withMaxOutputTokens(Integer maxOutputTokens) {
      this.maxOutputTokens = maxOutputTokens;
      return this;
    }

    /**
     * Declare the model's total context-window size in tokens. {@code null} (default) defers to the
     * provider's known value for the model id. Set this for a model the provider does not yet
     * enumerate so session compaction sizes against the real window instead of a conservative
     * backstop. See {@link ModelConfig#contextWindow()}.
     *
     * @param contextWindow the window in tokens; may be null
     * @return this builder
     */
    public Builder withContextWindow(Integer contextWindow) {
      this.contextWindow = contextWindow;
      return this;
    }

    /**
     * Sets the sequences that end generation when the model emits one. Absent by default; the list
     * is kept as given, not copied.
     *
     * @param stopSequences the stop sequences; may be null
     * @return this builder
     */
    public Builder withStopSequences(List<String> stopSequences) {
      this.stopSequences = stopSequences;
      return this;
    }

    /**
     * Sets the random seed for reproducible sampling. Absent by default; only the Gemini provider
     * sends it, the others ignore it.
     *
     * @param seed the seed; may be null
     * @return this builder
     */
    public Builder withSeed(Long seed) {
      this.seed = seed;
      return this;
    }

    /**
     * Sets how the model may use the tools it is offered. {@code null} (default) sends no tool
     * choice, so the provider's own default applies. A session applies it on every turn.
     *
     * @param toolChoice the tool choice; may be null
     * @return this builder
     */
    public Builder withToolChoice(ToolChoice toolChoice) {
      this.toolChoice = toolChoice;
      return this;
    }

    /**
     * Enable provider-native web search. Gemini maps this to Google Search grounding; Anthropic to
     * the {@code web_search} server tool. Providers without web search support fail fast at model
     * construction. Disabled by default.
     *
     * @param webSearch whether to enable web search
     * @return this builder
     */
    public Builder withWebSearch(boolean webSearch) {
      this.webSearch = webSearch;
      return this;
    }

    /**
     * Enable provider-native URL fetching. Gemini maps this to URL context; Anthropic to the {@code
     * web_fetch} server tool. Providers without web fetch support fail fast at model construction.
     * Disabled by default.
     *
     * @param webFetch whether to enable URL fetching
     * @return this builder
     */
    public Builder withWebFetch(boolean webFetch) {
      this.webFetch = webFetch;
      return this;
    }

    /**
     * Stable cache-routing key for providers whose prompt cache benefits from one (OpenAI {@code
     * prompt_cache_key}). See {@link ModelConfig#promptCacheKey()}.
     *
     * @param promptCacheKey the key; {@code null} (default) sends none
     * @return this builder
     */
    public Builder withPromptCacheKey(String promptCacheKey) {
      this.promptCacheKey = promptCacheKey;
      return this;
    }

    /**
     * Sets the longest wait for the next SSE line once the response headers arrived. Default 120 s.
     * See {@link ModelConfig#streamIdleTimeout()}.
     *
     * @param streamIdleTimeout the idle timeout; a streaming provider rejects {@code null} with
     *     {@link NullPointerException} when it opens a stream
     * @return this builder
     */
    public Builder withStreamIdleTimeout(Duration streamIdleTimeout) {
      this.streamIdleTimeout = streamIdleTimeout;
      return this;
    }

    /**
     * Override the provider's default API endpoint. See {@link ModelConfig#baseUrl()} for the
     * canonical URLs each provider falls back to when this is {@code null}.
     *
     * @param baseUrl the endpoint, used verbatim; {@code null} or blank selects the provider's own
     * @return this builder
     */
    public Builder withBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /**
     * Allow providers to continue conversations using provider-side state. Disable this to resend
     * local message history without sending or propagating provider conversation identifiers.
     * Enabled by default.
     *
     * @param providerContinuation whether provider-side continuation is allowed
     * @return this builder
     */
    public Builder withProviderContinuation(boolean providerContinuation) {
      this.providerContinuation = providerContinuation;
      return this;
    }

    /**
     * Select a provider API version. Providers that support version selection validate their values
     * during model construction; {@code null} restores the provider default.
     *
     * @param apiVersion the version; may be null
     * @return this builder
     */
    public Builder withApiVersion(String apiVersion) {
      this.apiVersion = apiVersion;
      return this;
    }

    /**
     * Configure whether structured-output exceptions retain raw model responses. Default {@link
     * RawOutputCapturePolicy#ENABLED}.
     *
     * @param rawOutputCapturePolicy the policy; {@code null} fails {@link #build()}
     * @return this builder
     */
    public Builder withRawOutputCapture(RawOutputCapturePolicy rawOutputCapturePolicy) {
      this.rawOutputCapturePolicy = rawOutputCapturePolicy;
      return this;
    }

    /**
     * Replace the current header map with the given entries. {@code null} clears all extra headers.
     * Names match case-insensitively against built-in provider headers and override them.
     *
     * @param headers the headers, copied; may be null
     * @return this builder
     */
    public Builder withHeaders(Map<String, String> headers) {
      this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
      return this;
    }

    /**
     * Add or replace a single extra HTTP header. Case-insensitive override against built-in
     * provider headers (e.g. setting {@code api-key} replaces the Azure-incompatible default {@code
     * Authorization} on the OpenAI provider). Repeated calls with the same name overwrite.
     *
     * @param name the header name; non-blank
     * @param value the header value; {@code null} fails {@link #build()}
     * @return this builder
     * @throws IllegalArgumentException if {@code name} is null or blank
     */
    public Builder withHeader(String name, String value) {
      if (Strings.isBlank(name)) {
        throw new IllegalArgumentException("header name is required");
      }
      this.headers.put(name, value);
      return this;
    }

    /**
     * Builds the configuration, validating it as the canonical constructor does.
     *
     * @return the configuration
     * @throws NullPointerException if the raw output capture policy, or a header value, is null
     * @throws IllegalArgumentException if the temperature or top-p is NaN or infinite
     */
    public ModelConfig build() {
      return new ModelConfig(
          apiKey,
          Optional.ofNullable(reasoning),
          connectTimeout,
          responseTimeout,
          temperature,
          topP,
          maxOutputTokens,
          contextWindow,
          stopSequences,
          seed,
          toolChoice,
          webSearch,
          webFetch,
          promptCacheKey,
          streamIdleTimeout,
          baseUrl,
          Map.copyOf(headers),
          providerContinuation,
          apiVersion,
          rawOutputCapturePolicy);
    }
  }
}
