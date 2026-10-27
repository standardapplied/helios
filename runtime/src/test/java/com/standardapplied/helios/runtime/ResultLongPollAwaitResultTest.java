/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.runtime.AuthenticationGate.Caller;
import com.standardapplied.helios.runtime.ServerStubs.RecordingResponse;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SerializedError;
import io.helidon.http.Status;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link ResultLongPoll#respond} through a recording response: every answer the long poll
 * can give, including the interrupted, failed and cancelled waits a black-box HTTP call cannot
 * force, and the identity ending before, during and after the wait.
 */
final class ResultLongPollAwaitResultTest {

  private static final Principal ALICE = new Principal("acme", "alice");
  private static final String UNAUTHENTICATED = "{\"error\":\"unauthenticated\"}";
  private static final String ABNORMAL = "{\"error\":\"session terminated abnormally\"}";

  private final StubSession session = new StubSession("sess-1");
  private final CompletableFuture<Void> identity = new CompletableFuture<>();
  private final RecordingResponse resp = new RecordingResponse();

  @Test
  void aResultIsAnswered200WithItsTypedBody() {
    session.terminate();

    poll("timeout=5");

    assertEquals(Status.OK_200, resp.status());
    assertEquals("application/json", resp.header("Content-Type"));
    var body = JsonBody.MAPPER.readValue(resp.body(), Map.class);
    assertEquals(List.of("type", "runId", "result"), List.copyOf(body.keySet()));
    assertEquals("Success", body.get("type"));
    assertEquals("ok", ((Map<?, ?>) body.get("result")).get("result"));
  }

  @Test
  void aWaitThatTimesOutIsAnswered204WithNoBody() {
    poll("timeout=0");

    assertEquals(Status.NO_CONTENT_204, resp.status());
    assertEquals("", resp.body());
    assertNull(resp.header("Content-Type"));
  }

  @Test
  void aTimedOutPollLeavesNothingWatchingAnUnfinishedResultWhileItsIdentityLives() {
    for (var i = 0; i < 1_000; i++) {
      var each = new RecordingResponse();
      ResultLongPoll.respond(
          session,
          session.result,
          ServerStubs.request("timeout=0"),
          each.response,
          JsonBody.MAPPER,
          caller());
      assertEquals(Status.NO_CONTENT_204, each.status());
    }

    assertEquals(0, session.result.getNumberOfDependents());
    assertEquals(0, identity.getNumberOfDependents());
  }

  @Test
  void anInterruptedWaitIsAnswered503AndKeepsTheInterrupt() {
    Thread.currentThread().interrupt();
    try {
      poll("timeout=60");

      assertEquals(Status.SERVICE_UNAVAILABLE_503, resp.status());
      assertEquals(
          "{\"error\":\"request interrupted while waiting for session result\"}", resp.body());
    } finally {
      assertTrue(Thread.interrupted(), "the interrupt must be kept");
    }
  }

  @Test
  void aFailedResultIsAnswered500WithoutTheCausesMessage() {
    session.result.completeExceptionally(new IllegalStateException("backend exploded: s3cr3t"));

    poll("timeout=5");

    assertEquals(Status.INTERNAL_SERVER_ERROR_500, resp.status());
    assertEquals(ABNORMAL, resp.body());
  }

  @Test
  void aCancelledResultIsAnswered500() {
    session.result.cancel(true);

    poll("timeout=5");

    assertEquals(Status.INTERNAL_SERVER_ERROR_500, resp.status());
    assertEquals(ABNORMAL, resp.body());
  }

  @Test
  void anEndedIdentityIsAnswered401EvenWhenTheResultIsReady() {
    session.terminate();
    identity.complete(null);

    poll("timeout=5");

    assertUnauthenticated();
  }

  @Test
  void anEndedIdentityIsAnswered401EvenWhenTheResultFailed() {
    session.result.completeExceptionally(new IllegalStateException("backend exploded"));
    identity.complete(null);

    poll("timeout=5");

    assertUnauthenticated();
  }

  @Test
  void anIdentityEndingWhileWaitingEndsTheWaitWith401() {
    var waiting =
        CompletableFuture.runAsync(
            () -> poll("timeout=" + ResultLongPoll.MAX_RESULT_TIMEOUT_SECONDS));
    Await.until(
        "the long poll to wait on the identity", () -> identity.getNumberOfDependents() > 0);

    identity.complete(null);

    Await.value("the long poll to end", waiting);
    assertUnauthenticated();
    assertFalse(session.result.isDone());
  }

  @Test
  void anErrorDuringExecutionKeepsItsFrames() {
    var carrying = SerializedError.of(new IllegalStateException("internal detail"));
    assertTrue(carrying.stackTrace().size() > 0, "test precondition");
    session.result.complete(
        new ResultMessage.ErrorDuringExecution(
            "sess-1", "run-1", carrying, Usage.of(1, 1), CostEstimate.zero(), Duration.ZERO));

    poll("timeout=5");

    assertEquals(Status.OK_200, resp.status());
    var body = JsonBody.MAPPER.readValue(resp.body(), Map.class);
    assertEquals("ErrorDuringExecution", body.get("type"));
    var error = (Map<?, ?>) ((Map<?, ?>) body.get("result")).get("error");
    assertEquals(carrying.message(), error.get("message"));
    assertEquals(carrying.stackTrace().size(), ((List<?>) error.get("stackTrace")).size());
  }

  private void poll(String query) {
    ResultLongPoll.respond(
        session,
        session.result,
        ServerStubs.request(query),
        resp.response,
        JsonBody.MAPPER,
        caller());
  }

  private Caller caller() {
    return new Caller(ALICE, identity, new Challenge(TestAuthenticator.CHALLENGE));
  }

  private void assertUnauthenticated() {
    assertEquals(Status.UNAUTHORIZED_401, resp.status());
    assertEquals(UNAUTHENTICATED, resp.body());
    assertEquals(TestAuthenticator.CHALLENGE, resp.header("WWW-Authenticate"));
  }
}
