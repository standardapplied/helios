/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.provider.Exchange;
import com.standardapplied.helios.core.provider.ProviderFailure;
import com.standardapplied.helios.core.provider.StreamingModel;
import com.standardapplied.helios.core.schema.StructuredContentParser;
import java.util.List;

/**
 * Runs one request through a provider's {@link Exchange} the way the provider's {@link
 * StreamingModel} does, for a test of the exchange's parts: the response of its completion, or the
 * exception its failure ends in.
 */
public final class ExchangeTurns {

  private static final StructuredContentParser.JsonAdapter NO_STRUCTURED_OUTPUT =
      new StructuredContentParser.JsonAdapter(
          json -> {
            throw new UnsupportedOperationException("no structured output");
          },
          (map, type) -> {
            throw new UnsupportedOperationException("no structured output");
          });

  private ExchangeTurns() {}

  /**
   * The response {@code exchange} answers {@code request} with.
   *
   * @param provider the provider name a retryable failure carries
   * @param failure creates the provider's exception
   */
  public static <R> Response<Void> chat(
      Exchange<R> exchange, R request, String provider, ProviderFailure failure) {
    try (var model =
        StreamingModel.<R>newBuilder()
            .withId("test")
            .withProvider(provider)
            .withConfig(ModelConfig.newBuilder().build())
            .withHttpClient(HttpClientFactory.create())
            .withRequests((messages, tools, schema) -> request)
            .withExchange(exchange)
            .withFailure(failure)
            .withJson(NO_STRUCTURED_OUTPUT)
            .build()) {
      return model.chat(List.of(), List.of());
    }
  }
}
