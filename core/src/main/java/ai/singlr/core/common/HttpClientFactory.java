/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import ai.singlr.core.model.ModelConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Factory and shared utilities for {@link HttpClient} instances used by provider modules.
 *
 * <p>Creates HttpClient instances with connection timeout from ModelConfig. Response timeout is
 * applied per-request via HttpRequest.Builder.timeout().
 */
public final class HttpClientFactory {

  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

  private HttpClientFactory() {}

  /**
   * Create an HttpClient configured with timeouts from ModelConfig.
   *
   * @param config the model configuration containing timeout settings
   * @return a new HttpClient instance
   */
  public static HttpClient create(ModelConfig config) {
    Duration connectTimeout =
        config != null && config.connectTimeout() != null
            ? config.connectTimeout()
            : DEFAULT_CONNECT_TIMEOUT;

    return HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  /**
   * Create an HttpClient with default settings.
   *
   * @return a new HttpClient instance with default timeouts
   */
  public static HttpClient create() {
    return create(null);
  }

  private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

  /**
   * Read an HTTP error body up to 64 KB, appending a truncation marker if the server pushed more.
   * Misconfigured proxies and gateways can return multi-megabyte HTML error pages; {@code
   * readAllBytes()} would buffer the lot before the caller sees anything.
   *
   * @param body the response body stream; non-null
   * @return the error body text, possibly truncated
   * @throws IOException if reading fails
   */
  public static String readBoundedErrorBody(InputStream body) throws IOException {
    var capped = body.readNBytes(MAX_ERROR_BODY_BYTES);
    var truncated = body.read() != -1;
    var text = new String(capped, StandardCharsets.UTF_8);
    return truncated
        ? text + "\n[truncated: error body exceeded " + MAX_ERROR_BODY_BYTES + " bytes]"
        : text;
  }

  /**
   * Shut down an {@link HttpClient} with a 5-second grace period. Calls {@code shutdown()}, waits
   * up to 5 seconds for in-flight requests, then forces with {@code shutdownNow()} if the timeout
   * expires.
   *
   * @param client the client to shut down; non-null
   */
  public static void shutdownGracefully(HttpClient client) {
    client.shutdown();
    try {
      if (!client.awaitTermination(Duration.ofSeconds(5))) {
        client.shutdownNow();
      }
    } catch (InterruptedException e) {
      client.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
