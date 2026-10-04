/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.RedirectTrap;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class HttpClientFactoryTest {

  @Test
  void createWithConfig() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withConnectTimeout(Duration.ofSeconds(30))
            .build();

    var client = HttpClientFactory.create(config);

    assertNotNull(client);
    assertEquals(Duration.ofSeconds(30), client.connectTimeout().orElse(null));
  }

  @Test
  void createWithNullConfig() {
    var client = HttpClientFactory.create(null);

    assertNotNull(client);
    assertEquals(Duration.ofSeconds(10), client.connectTimeout().orElse(null));
  }

  @Test
  void createWithDefaultSettings() {
    var client = HttpClientFactory.create();

    assertNotNull(client);
    assertEquals(Duration.ofSeconds(10), client.connectTimeout().orElse(null));
  }

  @Test
  void createWithNullConnectTimeout() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withConnectTimeout(null).build();

    var client = HttpClientFactory.create(config);

    assertNotNull(client);
    assertEquals(Duration.ofSeconds(10), client.connectTimeout().orElse(null));
  }

  @Test
  void neverFollowsRedirects() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();

    assertEquals(HttpClient.Redirect.NEVER, HttpClientFactory.create().followRedirects());
    assertEquals(HttpClient.Redirect.NEVER, HttpClientFactory.create(config).followRedirects());
  }

  static Stream<RedirectTrap.Scenario> redirectScenarios() {
    return RedirectTrap.scenarios();
  }

  @ParameterizedTest
  @MethodSource("redirectScenarios")
  void returnsEveryRedirectToTheCallerWithoutForwarding(RedirectTrap.Scenario scenario)
      throws Exception {
    var client = HttpClientFactory.create();
    try (var trap = RedirectTrap.open(scenario)) {
      var request =
          HttpRequest.newBuilder(URI.create(trap.originUrl()))
              .header("x-api-key", RedirectTrap.API_KEY)
              .header(RedirectTrap.CUSTOM_HEADER, RedirectTrap.CUSTOM_CREDENTIAL)
              .POST(HttpRequest.BodyPublishers.ofString(RedirectTrap.PROMPT))
              .build();

      var response = client.send(request, HttpResponse.BodyHandlers.discarding());

      assertEquals(scenario.status(), response.statusCode());
      trap.assertNothingForwarded();
    } finally {
      HttpClientFactory.shutdownGracefully(client);
    }
  }

  @Test
  void readBoundedErrorBodyCapsAtLimitAndMarksTruncation() throws Exception {
    var oversized = new byte[64 * 1024 + 1024];
    java.util.Arrays.fill(oversized, (byte) 'x');
    var result = HttpClientFactory.readBoundedErrorBody(new ByteArrayInputStream(oversized));
    assertTrue(result.contains("[truncated:"));
    assertTrue(result.length() <= 64 * 1024 + 100);
  }

  @Test
  void readBoundedErrorBodyReturnsExactBytesWhenUnderLimit() throws Exception {
    assertEquals(
        "hello",
        HttpClientFactory.readBoundedErrorBody(new ByteArrayInputStream("hello".getBytes())));
  }

  @Test
  void shutdownGracefullyClosesClient() {
    var client = HttpClientFactory.create();
    HttpClientFactory.shutdownGracefully(client);
    assertTrue(client.isTerminated());
  }
}
