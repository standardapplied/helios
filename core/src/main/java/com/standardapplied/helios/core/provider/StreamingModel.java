/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.schema.StructuredContentParser;
import com.standardapplied.helios.core.tool.Tool;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Objects;

/**
 * The {@link Model} of a streaming HTTP provider, assembled from the provider's parts: each turn
 * becomes the provider's request, runs through its {@link Exchange}, and is drained, parsed against
 * an output schema, or streamed. A structured turn that called tools is left unparsed: it is
 * intermediate, and a later text-only turn delivers the structured answer. Closing the model shuts
 * its HTTP client down. A provider builds one per model id and configuration through {@link
 * #newBuilder()}.
 *
 * @param <R> the provider's request type
 */
public final class StreamingModel<R> implements Model {

  private final String id;
  private final String provider;
  private final ModelConfig config;
  private final int defaultContextWindow;
  private final int maxOutputTokens;
  private final HttpClient httpClient;
  private final RequestFactory<R> requests;
  private final Exchange<R> exchange;
  private final StructuredOutput structured;

  private StreamingModel(Builder<R> builder) {
    this.id = builder.id;
    this.provider = builder.provider;
    this.config = builder.config;
    this.defaultContextWindow = builder.defaultContextWindow;
    this.maxOutputTokens = builder.maxOutputTokens;
    this.httpClient = builder.httpClient;
    this.requests = builder.requests;
    this.exchange = builder.exchange;
    this.structured = new StructuredOutput(builder.json, config.rawOutputCapturePolicy());
  }

  /**
   * A builder for a provider's model.
   *
   * @param <R> the provider's request type
   * @return an empty builder
   */
  public static <R> Builder<R> newBuilder() {
    return new Builder<>();
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public String provider() {
    return provider;
  }

  /** The configured context window, else the model's own; {@code 0} when neither is known. */
  @Override
  public int contextWindow() {
    return config.contextWindow() != null ? config.contextWindow() : defaultContextWindow;
  }

  @Override
  public int maxOutputTokens() {
    return maxOutputTokens;
  }

  @Override
  public RawOutputCapturePolicy rawOutputCapturePolicy() {
    return config.rawOutputCapturePolicy();
  }

  @Override
  public Response<Void> chat(List<Message> messages, List<Tool> tools) {
    return exchange.chat(requests.build(messages, tools, null));
  }

  @Override
  public <T> Response<T> chat(
      List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
    var request = requests.build(messages, tools, outputSchema.schema().toMap());
    return structured.of(exchange.chat(request), outputSchema);
  }

  @Override
  public CloseableIterator<StreamEvent> chatStream(List<Message> messages, List<Tool> tools) {
    return exchange.stream(requests.build(messages, tools, null));
  }

  /** Shuts the model's HTTP client down, waiting briefly for requests in flight. */
  @Override
  public void close() {
    HttpClientFactory.shutdownGracefully(httpClient);
  }

  /**
   * Assembles a {@link StreamingModel}. Every part is required except the two token counts, which
   * default to {@code 0} (unknown).
   *
   * @param <R> the provider's request type
   */
  public static final class Builder<R> {

    private String id;
    private String provider;
    private ModelConfig config;
    private int defaultContextWindow;
    private int maxOutputTokens;
    private HttpClient httpClient;
    private RequestFactory<R> requests;
    private Exchange<R> exchange;
    private StructuredContentParser.JsonAdapter json;

    private Builder() {}

    /**
     * The model id sent on the wire.
     *
     * @param id the model id
     * @return this builder
     */
    public Builder<R> withId(String id) {
      this.id = id;
      return this;
    }

    /**
     * The provider name, e.g. {@code "anthropic"}.
     *
     * @param provider the provider name
     * @return this builder
     */
    public Builder<R> withProvider(String provider) {
      this.provider = provider;
      return this;
    }

    /**
     * The model configuration; its context window, when set, overrides the model's own.
     *
     * @param config the configuration
     * @return this builder
     */
    public Builder<R> withConfig(ModelConfig config) {
      this.config = config;
      return this;
    }

    /**
     * The model's own context window, used when the configuration sets none.
     *
     * @param tokens the window in tokens, or {@code 0} when unknown
     * @return this builder
     */
    public Builder<R> withDefaultContextWindow(int tokens) {
      this.defaultContextWindow = tokens;
      return this;
    }

    /**
     * The most tokens one response may hold.
     *
     * @param tokens the ceiling in tokens, or {@code 0} when unknown
     * @return this builder
     */
    public Builder<R> withMaxOutputTokens(int tokens) {
      this.maxOutputTokens = tokens;
      return this;
    }

    /**
     * The client the provider's requests go through; the model owns it and closes it.
     *
     * @param httpClient the client
     * @return this builder
     */
    public Builder<R> withHttpClient(HttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    /**
     * Builds the provider's request for each turn.
     *
     * @param requests the request factory
     * @return this builder
     */
    public Builder<R> withRequests(RequestFactory<R> requests) {
      this.requests = requests;
      return this;
    }

    /**
     * Runs each turn's request.
     *
     * @param exchange the exchange
     * @return this builder
     */
    public Builder<R> withExchange(Exchange<R> exchange) {
      this.exchange = exchange;
      return this;
    }

    /**
     * Reads structured output, e.g. a {@link JsonBinding} over the provider's JSON mapper.
     *
     * @param json the JSON adapter
     * @return this builder
     */
    public Builder<R> withJson(StructuredContentParser.JsonAdapter json) {
      this.json = json;
      return this;
    }

    /**
     * The model.
     *
     * @return a new model
     * @throws IllegalArgumentException if the id or provider is blank, or a token count negative
     * @throws NullPointerException if a required part is missing
     */
    public StreamingModel<R> build() {
      requireText(id, "id");
      requireText(provider, "provider");
      requireNonNegative(defaultContextWindow, "contextWindow");
      requireNonNegative(maxOutputTokens, "maxOutputTokens");
      Objects.requireNonNull(config, "config must not be null");
      Objects.requireNonNull(httpClient, "httpClient must not be null");
      Objects.requireNonNull(requests, "requests must not be null");
      Objects.requireNonNull(exchange, "exchange must not be null");
      Objects.requireNonNull(json, "json must not be null");
      return new StreamingModel<>(this);
    }

    private static void requireText(String value, String name) {
      if (Strings.isBlank(value)) {
        throw new IllegalArgumentException(name + " must not be blank");
      }
    }

    private static void requireNonNegative(int tokens, String name) {
      if (tokens < 0) {
        throw new IllegalArgumentException(name + " must not be negative: " + tokens);
      }
    }
  }
}
