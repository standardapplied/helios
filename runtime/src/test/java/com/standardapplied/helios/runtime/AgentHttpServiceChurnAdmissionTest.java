/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static com.standardapplied.helios.runtime.AdmissionHarness.assertRefused;
import static com.standardapplied.helios.runtime.AdmissionHarness.messages;
import static com.standardapplied.helios.runtime.AdmissionHarness.result;
import static com.standardapplied.helios.runtime.ResponseBodies.rest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.QueryEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * An event stream or result poll holds its principal's request slot until it ends, even after its
 * session is deleted, so creating and deleting sessions never lifts the bound. A revoked event
 * stream holds its slot until the event it is still writing is done, so revoking credentials never
 * lifts it either.
 */
final class AgentHttpServiceChurnAdmissionTest {

  private static final QueryEvent.AssistantText LARGE =
      new QueryEvent.AssistantText(
          "held", "run-1", 0L, Instant.EPOCH, "x".repeat(32 * 1024 * 1024));

  private final AdmissionHarness harness = new AdmissionHarness();

  @AfterEach
  void closeHarness() {
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
    var server = harness.server(harness.registry(), 2);
    var deleted = harness.create(server, "alice");
    assertEquals(
        202, harness.sendAdmitted(server, "POST", messages(deleted), "alice").statusCode());
    var held = waitingPoll(server, deleted, "alice");
    delete(server, deleted);

    assertRefusedWhileHoldingOneMore(server);

    harness.sessions.get(deleted).terminate();
    assertEquals(200, Await.value("the held poll to be answered", held).statusCode());
    assertEquals(201, harness.sendAdmitted(server, "POST", "/sessions", "alice").statusCode());
  }

  /**
   * The event is larger than every buffer between the server and the client, so its write stays
   * under way while the client reads no further than its first line.
   */
  @Test
  void aRevokedEventStreamKeepsItsAdmissionUntilTheEventItIsWritingIsDone() throws IOException {
    var server = harness.server(harness.registry(), 1);
    var sessionId = harness.create(server, "alice");
    var events = harness.openEventsOnOwnConnectionAdmitted(server, sessionId, "alice");
    var session = harness.sessions.get(sessionId);
    session.streams.getFirst().push(LARGE);
    assertEquals(
        "event:AssistantText",
        Await.value(
            "the event's write to start", CompletableFuture.supplyAsync(() -> line(events))));

    harness.tokens.revoke("alice");
    var refused = open(server, sessionId, "alice");

    assertStreamRefused(refused);
    events.close();
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

  private static void assertStreamRefused(HttpResponse<InputStream> resp) {
    assertEquals(429, resp.statusCode());
    assertEquals(
        Optional.of(Overload.RETRY_AFTER_SECONDS), resp.headers().firstValue("Retry-After"));
    assertEquals(AdmissionHarness.TOO_MANY_REQUESTS, ResponseBodies.text(resp.body()));
  }

  private static String line(BufferedReader reader) {
    try {
      return reader.readLine();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
