/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.testing.ScriptedModel;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A client that stops reading its event stream holds the server's reader in a write once the
 * connection's buffers fill, so the session's stream fills and misses events. The model streams
 * until the session logs that first miss, then the client reads on and reads {@code event:
 * EventsDropped}, with the marker's fields, where its stream missed them.
 */
final class SessionEventStreamHeldClientTest {

  private static final Principal ALICE = new Principal("acme", "alice");
  private static final String SESSION_ID = "sess-held-client";

  private final LogCapture misses =
      LogCapture.of("com.standardapplied.helios.session.SessionEventPublisher");
  private final CountDownLatch missed = new CountDownLatch(1);
  private final SessionRegistry registry = SessionRegistry.inMemory();
  private final RuntimeServer server =
      RuntimeServer.builder()
          .withRegistry(registry)
          .withOptionsFactory(request -> options())
          .withAuthenticator(
              TrustedProxyAuthenticator.newBuilder()
                  .withTrustedProxy(InetAddress.getLoopbackAddress())
                  .build())
          .build();
  private final HttpClient http = HttpClient.newHttpClient();

  @AfterEach
  void stop() {
    http.shutdownNow();
    server.close();
    misses.close();
  }

  @Test
  void aHeldClientReadsEventsDroppedWithItsFieldsWhereItsStreamMissedEvents() throws IOException {
    var session = registry.create(options(), ALICE);
    try (var client = openEvents()) {
      read(client, "Ready");
      session.send(UserMessage.text("go"));
      Await.latch("the session's stream to miss an event", missed);

      var events = read(client, "RunEnded");
      var dropped = data(events, "EventsDropped");
      assertEquals(
          List.of("sessionId", "runId", "turnIndex", "timestamp", "count"),
          List.copyOf(dropped.keySet()));
      assertEquals(
          List.of(
              SESSION_ID,
              data(events, "RunEnded").get("runId"),
              data(events, "AssistantText").get("turnIndex")),
          List.of(dropped.get("sessionId"), dropped.get("runId"), dropped.get("turnIndex")));
      Instant.parse((String) dropped.get("timestamp"));
      assertTrue(((Number) dropped.get("count")).longValue() > 0, dropped::toString);
    }
  }

  private BufferedReader openEvents() {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + server.port() + "/v1/sessions/" + SESSION_ID + "/events"))
            .header("X-Authenticated-Tenant", ALICE.tenant())
            .header("X-Authenticated-Subject", ALICE.subject())
            .build();
    var response =
        Await.value(
            "the event stream to open", http.sendAsync(request, BodyHandlers.ofInputStream()));
    assertEquals(200, response.statusCode());
    return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
  }

  /**
   * Read SSE events, each as its name and data, up to and including the first named {@code last}.
   */
  private static List<Map.Entry<String, String>> read(BufferedReader client, String last) {
    return Await.value(
        "the client to read " + last,
        CompletableFuture.supplyAsync(
            () -> {
              var events = new ArrayList<Map.Entry<String, String>>();
              var name = "";
              while (events.isEmpty() || !last.equals(events.getLast().getKey())) {
                var line = readLine(client);
                if (line.startsWith("event:")) {
                  name = line.substring("event:".length());
                } else if (line.startsWith("data:")) {
                  events.add(Map.entry(name, line.substring("data:".length())));
                }
              }
              return events;
            }));
  }

  private static String readLine(BufferedReader client) {
    try {
      var line = client.readLine();
      if (line == null) {
        throw new IllegalStateException("the event stream ended");
      }
      return line;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Map<?, ?> data(List<Map.Entry<String, String>> events, String name) {
    var data =
        events.stream().filter(e -> e.getKey().equals(name)).findFirst().orElseThrow().getValue();
    return JsonMapper.builder().build().readValue(data, Map.class);
  }

  /**
   * Options whose model streams 16 KiB text deltas until the session logs a miss as a WARNING, then
   * releases {@link #missed} and stops.
   */
  private SessionOptions options() {
    var text = "x".repeat(16 * 1024);
    var stream =
        new CloseableIterator<ModelChunk>() {
          private boolean stopped;

          @Override
          public boolean hasNext() {
            return !stopped;
          }

          @Override
          public ModelChunk next() {
            if (misses.records().stream().noneMatch(r -> r.getLevel() == Level.WARNING)) {
              return new ModelChunk.TextDelta(text);
            }
            missed.countDown();
            stopped = true;
            return new ModelChunk.MessageStop(FinishReason.STOP.name(), Usage.of(1, 1), Map.of());
          }

          @Override
          public void close() {}
        };
    return SessionOptions.newBuilder()
        .withModel(ScriptedModel.newBuilder().withStreamTurn(stream).build())
        .withSessionId(SESSION_ID)
        .build();
  }
}
