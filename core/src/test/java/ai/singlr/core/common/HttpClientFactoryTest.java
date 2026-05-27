/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.ModelConfig;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

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
  void followsRedirects() {
    var client = HttpClientFactory.create();

    assertEquals(HttpClient.Redirect.NORMAL, client.followRedirects());
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
