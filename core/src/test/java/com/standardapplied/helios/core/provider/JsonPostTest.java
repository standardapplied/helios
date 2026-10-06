/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ModelConfig;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class JsonPostTest {

  @Test
  void postsTheBodyAsJsonWithTheProvidersHeadersAndTheConfiguredOverrides() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("k")
            .withHeaders(Map.of("X-API-KEY", "override", "x-extra", "1"))
            .withResponseTimeout(Duration.ofSeconds(30))
            .build();

    var request =
        JsonPost.to(
            URI.create("https://api.example/v1/chat"),
            config,
            Map.of("x-api-key", "k"),
            "{\"a\":1}");

    assertEquals("POST", request.method());
    assertEquals(URI.create("https://api.example/v1/chat"), request.uri());
    assertEquals(Optional.of("application/json"), request.headers().firstValue("Content-Type"));
    assertEquals(Optional.of("override"), request.headers().firstValue("x-api-key"));
    assertEquals(Optional.of("1"), request.headers().firstValue("x-extra"));
    assertEquals(Optional.of(Duration.ofSeconds(30)), request.timeout());
    assertEquals("{\"a\":1}", body(request));
  }

  @Test
  void noConfiguredResponseTimeoutLeavesTheRequestWithout() {
    var request =
        JsonPost.to(
            URI.create("https://api.example"),
            ModelConfig.newBuilder().withApiKey("k").withResponseTimeout(null).build(),
            Map.of(),
            "{}");

    assertTrue(request.timeout().isEmpty());
  }

  @Test
  void toBaseUrlPostsToTheConfiguredBaseUrlOrTheProvidersDefault() {
    var configured = ModelConfig.newBuilder().withBaseUrl("http://proxy.local/v1").build();
    var unconfigured = ModelConfig.newBuilder().withApiKey("k").build();

    assertEquals(
        URI.create("http://proxy.local/v1"),
        JsonPost.toBaseUrl("https://api.example", configured, Map.of(), "{}").uri());
    assertEquals(
        URI.create("https://api.example"),
        JsonPost.toBaseUrl("https://api.example", unconfigured, Map.of(), "{}").uri());
  }

  private static String body(HttpRequest request) {
    var chunks = new ArrayList<ByteBuffer>();
    request
        .bodyPublisher()
        .orElseThrow()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(ByteBuffer item) {
                chunks.add(item);
              }

              @Override
              public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
              }

              @Override
              public void onComplete() {}
            });
    var text = new StringBuilder();
    for (var chunk : List.copyOf(chunks)) {
      text.append(StandardCharsets.UTF_8.decode(chunk));
    }
    return text.toString();
  }
}
