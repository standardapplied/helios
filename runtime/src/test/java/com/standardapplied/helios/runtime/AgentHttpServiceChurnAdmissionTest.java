/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static com.standardapplied.helios.runtime.AdmissionHarness.assertRefused;
import static com.standardapplied.helios.runtime.AdmissionHarness.result;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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
 * sessions never lifts the bounds. A revoked event stream holds its admission until the event it is
 * still serializing is done, so revoking credentials never lifts them either.
 */
final class AgentHttpServiceChurnAdmissionTest {

  private static final QueryEvent.AssistantText EVENT =
      new QueryEvent.AssistantText("held", 0L, Instant.EPOCH, "held");

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
    harness.openEventsAdmitted(server, replacement, "alice").body().close();
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
            held(ResultMessage.Cancelled.class));
    var deleted = harness.create(server, "alice");
    var held = waitingPoll(server, deleted, "alice");
    assertEquals(204, harness.send(server, "DELETE", path(deleted), "alice").statusCode());
    Await.latch("the cancelled result to be serialized", serializing);
    var replacement = harness.create(server, "alice");

    var aliceRefused = harness.send(server, "GET", result(replacement), "alice");
    var bobs = waitingPoll(server, harness.create(server, "bob"), "bob");
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
    assertEquals(
        200, harness.sendAdmitted(server, "GET", result(replacement), "alice").statusCode());
    harness.sessions.values().forEach(StubSession::terminate);
    assertEquals(200, Await.value("bob's poll to be answered", bobs).statusCode());
  }

  @Test
  void aRevokedEventStreamKeepsItsAdmissionUntilTheEventItIsSerializingIsDone() throws IOException {
    var server =
        harness.server(
            harness.registry(),
            RequestLimits.newBuilder().withMaxEventStreamsPerSession(1).build(),
            held(QueryEvent.AssistantText.class));
    var sessionId = harness.create(server, "alice");
    var revoked =
        Await.value(
            "the event stream to be answered",
            harness.openEventsOnOwnConnection(server, sessionId, "alice"));
    var afterReady = afterReady(revoked);
    var session = harness.sessions.get(sessionId);
    var delivery = CompletableFuture.runAsync(() -> session.subscribers.getFirst().onNext(EVENT));
    Await.latch("the event to be serialized", serializing);

    harness.tokens.revoke("alice");
    var streamed =
        Await.value(
            "the stream to end", CompletableFuture.supplyAsync(() -> afterReady.lines().toList()));
    var refused = open(server, sessionId, "alice");

    assertEquals(List.of(), streamed);
    assertStreamRefused(refused, 429, "{\"error\":\"too many event streams on this session\"}");
    assertFalse(delivery.isDone());
    release.countDown();
    Await.value("the held delivery to end", delivery);
    harness.openEventsAdmitted(server, sessionId, "alice").body().close();
  }

  private HttpResponse<InputStream> open(RuntimeServer server, String sessionId, String token) {
    return Await.value(
        "the event stream to be answered", harness.openEvents(server, sessionId, token));
  }

  /** Send a result poll and return once it waits on the session's result. */
  private CompletableFuture<HttpResponse<String>> waitingPoll(
      RuntimeServer server, String sessionId, String token) {
    var result = harness.sessions.get(sessionId).result;
    var watchers = result.getNumberOfDependents();
    var poll = harness.sendAsync(server, "GET", result(sessionId), token);
    Await.until(
        "the poll to wait on the result", () -> result.getNumberOfDependents() == watchers + 1);
    return poll;
  }

  private SessionRegistry.Builder oneSessionPerPrincipal() {
    return harness.registry().withMaxSessionsPerPrincipal(1);
  }

  /** A mapper that holds the serialization of every {@code type} until {@link #release}. */
  private <T> JsonMapper held(Class<T> type) {
    return JsonMapper.builder()
        .addModule(new SimpleModule("held").addSerializer(type, new HeldSerializer<>(type)))
        .build();
  }

  private static String path(String sessionId) {
    return "/sessions/" + sessionId;
  }

  private static void assertStreamRefused(HttpResponse<InputStream> resp, int status, String body) {
    assertEquals(status, resp.statusCode());
    assertEquals(
        Optional.of(Overload.RETRY_AFTER_SECONDS), resp.headers().firstValue("Retry-After"));
    assertEquals(body, ResponseBodies.text(resp.body()));
  }

  /** Read {@code resp}'s {@code Ready} event, so its subscription is live, and return the rest. */
  private static BufferedReader afterReady(HttpResponse<InputStream> resp) {
    var reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
    var ready =
        CompletableFuture.supplyAsync(() -> List.of(line(reader), line(reader), line(reader)));
    assertEquals(List.of("event:Ready", "data:{}", ""), Await.value("the Ready event", ready));
    return reader;
  }

  private static String line(BufferedReader reader) {
    try {
      return reader.readLine();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void drain(HttpResponse<InputStream> resp) {
    Await.value(
        "the stream to end", CompletableFuture.supplyAsync(() -> ResponseBodies.text(resp.body())));
  }

  private final class HeldSerializer<T> extends StdSerializer<T> {

    private HeldSerializer(Class<T> type) {
      super(type);
    }

    @Override
    public void serialize(T value, JsonGenerator gen, SerializationContext ctxt) {
      serializing.countDown();
      Await.latch("the test to release the serialization", release);
      gen.writeString("held");
    }
  }
}
