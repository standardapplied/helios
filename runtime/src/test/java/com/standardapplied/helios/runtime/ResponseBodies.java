/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.test.Await;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Reads a streamed HTTP response body: to its end, or as an event stream after its Ready event. */
final class ResponseBodies {

  private ResponseBodies() {}

  /** Read {@code body} to its end as UTF-8, then close it. */
  static String text(InputStream body) {
    try (body) {
      return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Read {@code resp}'s {@code Ready} event and return the rest of the stream. The server sends a
   * stream's headers before it subscribes to the session; {@code Ready} is sent once subscribed.
   */
  static BufferedReader afterReady(HttpResponse<InputStream> resp) {
    var reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
    var ready =
        CompletableFuture.supplyAsync(() -> List.of(line(reader), line(reader), line(reader)));
    assertEquals(List.of("event:Ready", "data:{}", ""), Await.value("the Ready event", ready));
    return reader;
  }

  /** Read {@code reader} to its end, once the server ends the stream. */
  static List<String> rest(BufferedReader reader) {
    return Await.value(
        "the stream to end", CompletableFuture.supplyAsync(() -> reader.lines().toList()));
  }

  private static String line(BufferedReader reader) {
    try {
      return reader.readLine();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
