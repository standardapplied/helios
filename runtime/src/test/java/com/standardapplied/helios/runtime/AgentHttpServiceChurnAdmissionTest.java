/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static com.standardapplied.helios.runtime.AdmissionHarness.assertRefused;
import static com.standardapplied.helios.runtime.AdmissionHarness.result;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.ResultMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * An event stream or result poll holds its principal's and the service's admission until it ends,
 * even after its session is deleted and its session capacity reused, so creating and deleting
 * sessions never lifts the bounds.
 */
final class AgentHttpServiceChurnAdmissionTest {

  private final AdmissionHarness harness = new AdmissionHarness();
  private final CountDownLatch serializing = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void closeHarness() {
    release.countDown();
    harness.close();
  }

  @Test
  void anEventStreamOfADeletedSessionStillCountsAgainstItsPrincipalAndTheService()
      throws IOException {
    var server =
        harness.server(
            oneSessionPerPrincipal(),
            RequestLimits.newBuilder()
                .withMaxEventStreams(2)
                .withMaxEventStreamsPerPrincipal(1)
                .build());
    var deleted = harness.create(server, "alice");
    var held = open(server, deleted, "alice");
    assertEquals(204, harness.send(server, "DELETE", path(deleted), "alice").statusCode());
    var replacement = harness.create(server, "alice");

    var aliceRefused = open(server, replacement, "alice");
    var bobs = open(server, harness.create(server, "bob"), "bob");
    var carolRefused = open(server, harness.create(server, "carol"), "carol");

    assertStreamRefused(aliceRefused, 429, "{\"error\":\"too many event streams open\"}");
    assertStreamRefused(carolRefused, 503, "{\"error\":\"server at event stream capacity\"}");
    assertEquals(new AdmissionCounts(2, 2), server.counters().eventStreams());

    harness.sessions.get(deleted).completeEvents();
    drain(held);
    var reopened = open(server, replacement, "alice");
    assertEquals(200, reopened.statusCode());
    reopened.body().close();
    bobs.body().close();
  }

  @Test
  void aResultPollOfADeletedSessionStillCountsAgainstItsPrincipalAndTheService() {
    var server =
        harness.server(
            oneSessionPerPrincipal(),
            RequestLimits.newBuilder()
                .withMaxResultPolls(2)
                .withMaxResultPollsPerPrincipal(1)
                .build(),
            heldCancellations());
    var deleted = harness.create(server, "alice");
    var held = harness.sendAsync(server, "GET", result(deleted), "alice");
    var session = harness.sessions.get(deleted);
    Await.until("the poll to wait on the result", () -> session.result.getNumberOfDependents() > 0);
    assertEquals(204, harness.send(server, "DELETE", path(deleted), "alice").statusCode());
    Await.latch("the cancelled result to be serialized", serializing);
    var replacement = harness.create(server, "alice");

    var aliceRefused = harness.send(server, "GET", result(replacement), "alice");
    var bobs = harness.sendAsync(server, "GET", result(harness.create(server, "bob")), "bob");
    var carolRefused =
        harness.send(server, "GET", result(harness.create(server, "carol")), "carol");

    assertEquals(429, aliceRefused.statusCode());
    assertRefused(aliceRefused, "{\"error\":\"too many result polls waiting\"}");
    assertEquals(503, carolRefused.statusCode());
    assertRefused(carolRefused, "{\"error\":\"server at result poll capacity\"}");
    assertEquals(new AdmissionCounts(2, 2), server.counters().resultPolls());

    release.countDown();
    assertEquals(200, Await.value("the held poll to be answered", held).statusCode());
    harness.sessions.get(replacement).terminate();
    assertEquals(200, harness.send(server, "GET", result(replacement), "alice").statusCode());
    harness.sessions.values().forEach(StubSession::terminate);
    assertEquals(200, Await.value("bob's poll to be answered", bobs).statusCode());
  }

  private HttpResponse<InputStream> open(RuntimeServer server, String sessionId, String token) {
    return Await.value(
        "the event stream to be answered", harness.openEvents(server, sessionId, token));
  }

  private SessionRegistry.Builder oneSessionPerPrincipal() {
    return harness.registry().withMaxSessionsPerPrincipal(1);
  }

  /** A mapper that holds the serialization of a cancelled result until {@link #release}. */
  private JsonMapper heldCancellations() {
    return JsonMapper.builder()
        .addModule(
            new SimpleModule("hold-cancelled-result")
                .addSerializer(ResultMessage.Cancelled.class, new HeldSerializer()))
        .build();
  }

  private static String path(String sessionId) {
    return "/sessions/" + sessionId;
  }

  private static void assertStreamRefused(HttpResponse<InputStream> resp, int status, String body)
      throws IOException {
    assertEquals(status, resp.statusCode());
    assertEquals(
        Optional.of(Overload.RETRY_AFTER_SECONDS), resp.headers().firstValue("Retry-After"));
    try (var in = resp.body()) {
      assertEquals(body, new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private static void drain(HttpResponse<InputStream> resp) {
    Await.value(
        "the stream to end",
        CompletableFuture.supplyAsync(
            () -> {
              try (var in = resp.body()) {
                return in.readAllBytes().length;
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            }));
  }

  private final class HeldSerializer extends StdSerializer<ResultMessage.Cancelled> {

    private HeldSerializer() {
      super(ResultMessage.Cancelled.class);
    }

    @Override
    public void serialize(
        ResultMessage.Cancelled value, JsonGenerator gen, SerializationContext ctxt) {
      serializing.countDown();
      Await.latch("the test to release the serialization", release);
      gen.writeString(value.sessionId());
    }
  }
}
