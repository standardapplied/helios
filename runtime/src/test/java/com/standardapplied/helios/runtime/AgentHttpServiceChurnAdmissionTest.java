/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static com.standardapplied.helios.runtime.AdmissionHarness.assertRefused;
import static com.standardapplied.helios.runtime.AdmissionHarness.result;
import static com.standardapplied.helios.runtime.ResponseBodies.rest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
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
 * An event stream or result poll holds its principal's request slot until it ends, even after its
 * session is deleted, so creating and deleting sessions never lifts the bound. A revoked event
 * stream holds its slot until the event it is still serializing is done, so revoking credentials
 * never lifts it either.
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
  void anEventStreamOfADeletedSessionHoldsItsSlotUntilItEnds() {
    var server = harness.server(harness.registry(), 2);
    var deleted = harness.create(server, "alice");
    var held = harness.openEventsAdmitted(server, deleted, "alice");
    delete(server, deleted);

    assertRefusedWhileHoldingOneMore(server);

    harness.sessions.get(deleted).completeEvents();
    assertEquals(List.of(), rest(held));
    assertEquals(201, harness.sendAdmitted(server, "POST", "/sessions", "alice").statusCode());
  }

  @Test
  void aResultPollOfADeletedSessionHoldsItsSlotUntilItIsAnswered() {
    var server =
        harness.server(
            harness.registry(), AdmissionHarness.limits(2), held(ResultMessage.Cancelled.class));
    var deleted = harness.create(server, "alice");
    var held = waitingPoll(server, deleted, "alice");
    delete(server, deleted);
    Await.latch("the cancelled result to be serialized", serializing);

    assertRefusedWhileHoldingOneMore(server);

    release.countDown();
    assertEquals(200, Await.value("the held poll to be answered", held).statusCode());
    assertEquals(201, harness.sendAdmitted(server, "POST", "/sessions", "alice").statusCode());
  }

  @Test
  void aRevokedEventStreamKeepsItsAdmissionUntilTheEventItIsSerializingIsDone() throws IOException {
    var server =
        harness.server(
            harness.registry(), AdmissionHarness.limits(1), held(QueryEvent.AssistantText.class));
    var sessionId = harness.create(server, "alice");
    var events = harness.openEventsOnOwnConnectionAdmitted(server, sessionId, "alice");
    var session = harness.sessions.get(sessionId);
    var delivery = CompletableFuture.runAsync(() -> session.subscribers.getFirst().onNext(EVENT));
    Await.latch("the event to be serialized", serializing);

    harness.tokens.revoke("alice");
    var streamed = rest(events);
    var refused = open(server, sessionId, "alice");

    assertEquals(List.of(), streamed);
    assertStreamRefused(refused);
    assertFalse(delivery.isDone());
    release.countDown();
    Await.value("the held delivery to end", delivery);
    harness.openEventsAdmitted(server, sessionId, "alice").close();
  }

  /**
   * With one of alice's two slots held by a request on a deleted session, hold the other with a
   * stream on a new session and find her next request refused.
   */
  private void assertRefusedWhileHoldingOneMore(RuntimeServer server) {
    var other = harness.create(server, "alice");
    harness.openEventsAdmitted(server, other, "alice");

    var refused = harness.send(server, "GET", result(other), "alice");

    assertEquals(429, refused.statusCode());
    assertRefused(refused, AdmissionHarness.TOO_MANY_REQUESTS);
    harness.sessions.get(other).completeEvents();
  }

  private void delete(RuntimeServer server, String sessionId) {
    assertEquals(
        204,
        harness.sendAdmitted(server, "DELETE", "/sessions/" + sessionId, "alice").statusCode());
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

  /** A mapper that holds the serialization of every {@code type} until {@link #release}. */
  private <T> JsonMapper held(Class<T> type) {
    return JsonMapper.builder()
        .addModule(new SimpleModule("held").addSerializer(type, new HeldSerializer<>(type)))
        .build();
  }

  private static void assertStreamRefused(HttpResponse<InputStream> resp) {
    assertEquals(429, resp.statusCode());
    assertEquals(
        Optional.of(Overload.RETRY_AFTER_SECONDS), resp.headers().firstValue("Retry-After"));
    assertEquals(AdmissionHarness.TOO_MANY_REQUESTS, ResponseBodies.text(resp.body()));
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
