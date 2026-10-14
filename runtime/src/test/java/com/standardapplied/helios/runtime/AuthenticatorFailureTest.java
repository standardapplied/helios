/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import io.helidon.http.HeaderNames;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * An authenticator that cannot decide is answered {@code 503}, and neither the answer nor the log
 * quotes what it was given or what it said.
 */
final class AuthenticatorFailureTest {

  private static final String SECRET = "s3cr3t-credential";

  @Test
  void aThrowingOrNullAnsweringAuthenticatorIs503AndNeverRevealsTheCredential() throws Exception {
    var records = new CopyOnWriteArrayList<LogRecord>();
    var logger = Logger.getLogger(AgentHttpService.class.getName());
    var handler = recordingHandler(records);
    var http = HttpClient.newBuilder().connectTimeout(Await.HANG_GUARD).build();
    logger.addHandler(handler);
    try (http;
        var server =
            RuntimeServer.builder()
                .withRegistry(SessionRegistry.inMemory())
                .withOptionsFactory(
                    sessionId -> {
                      throw new AssertionError("no session may be created");
                    })
                .withAuthenticator(
                    request -> {
                      if (request.headers().contains(HeaderNames.AUTHORIZATION)) {
                        throw new IllegalStateException("cannot verify " + SECRET);
                      }
                      return null;
                    })
                .build()) {
      var uri = URI.create("http://127.0.0.1:" + server.port() + "/v1/sessions");
      var throwing =
          http.send(
              HttpRequest.newBuilder(uri)
                  .timeout(Await.HANG_GUARD)
                  .header("Authorization", "Bearer " + SECRET)
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              BodyHandlers.ofString());
      var answeringNull =
          http.send(
              HttpRequest.newBuilder(uri)
                  .timeout(Await.HANG_GUARD)
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              BodyHandlers.ofString());

      assertEquals(503, throwing.statusCode());
      assertEquals("{\"error\":\"authentication unavailable\"}", throwing.body());
      assertEquals(503, answeringNull.statusCode());
      assertEquals(0, server.registry().size());
    } finally {
      logger.removeHandler(handler);
    }
    var failures =
        records.stream().filter(r -> r.getMessage().startsWith("authenticator")).toList();
    assertEquals(
        List.of(
            "authenticator failed with java.lang.IllegalStateException",
            "authenticator failed with java.lang.NullPointerException"),
        failures.stream().map(LogRecord::getMessage).toList());
    failures.forEach(r -> assertNull(r.getThrown()));
    assertTrue(records.stream().noneMatch(r -> String.valueOf(r.getMessage()).contains(SECRET)));
  }

  private static Handler recordingHandler(List<LogRecord> records) {
    return new Handler() {
      @Override
      public void publish(LogRecord logRecord) {
        records.add(logRecord);
      }

      @Override
      public void flush() {}

      @Override
      public void close() {}
    };
  }
}
