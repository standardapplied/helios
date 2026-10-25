/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static com.standardapplied.helios.runtime.AdmissionHarness.BURST;
import static com.standardapplied.helios.runtime.AdmissionHarness.TOO_MANY_REQUESTS;
import static com.standardapplied.helios.runtime.AdmissionHarness.assertRefused;
import static com.standardapplied.helios.runtime.AdmissionHarness.events;
import static com.standardapplied.helios.runtime.AdmissionHarness.interrupt;
import static com.standardapplied.helios.runtime.AdmissionHarness.messages;
import static com.standardapplied.helios.runtime.AdmissionHarness.result;
import static com.standardapplied.helios.runtime.AdmissionHarness.textBody;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.test.Await;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Every authenticated request takes one of its principal's request slots before any work and holds
 * it until it is answered, whatever the route; message bodies and steering-queue entries are
 * bounded on top of that.
 */
final class AgentHttpServiceRequestAdmissionTest {

  private static final String TOO_LARGE = "{\"error\":\"request body too large\"}";

  /** Helidon refuses a body declared over its {@code maxPayloadSize} before routing, bodiless. */
  private static final String HELIDONS_EMPTY_BODY = "";

  private final AdmissionHarness harness = new AdmissionHarness();

  @AfterEach
  void closeHarness() {
    harness.close();
  }

  @Test
  void aBodyOverTheBoundIsAnswered413BeforeAnythingIsQueued() {
    var max = 32;
    var server = harness.server(harness.registry(), new RequestLimits(max, 32));
    var sessionId = harness.create(server, "alice");
    var overBound = textBody(max + 1);

    var declared =
        harness.send(
            server, "POST", messages(sessionId), "alice", BodyPublishers.ofByteArray(overBound));
    var chunked = harness.send(server, "POST", messages(sessionId), "alice", chunked(overBound));
    var interrupt =
        harness.send(
            server, "POST", interrupt(sessionId), "alice", BodyPublishers.ofByteArray(overBound));

    assertEquals(
        List.of(413, 413, 413),
        List.of(declared, chunked, interrupt).stream().map(HttpResponse::statusCode).toList());
    assertEquals(HELIDONS_EMPTY_BODY, declared.body());
    assertEquals(TOO_LARGE, chunked.body());
    assertEquals(HELIDONS_EMPTY_BODY, interrupt.body());
    var session = harness.sessions.get(sessionId);
    assertEquals(0, session.queue.size());
    var atBound =
        harness.send(server, "POST", messages(sessionId), "alice", chunked(textBody(max)));
    assertEquals(202, atBound.statusCode());
    assertEquals(1, session.queue.size());
  }

  @Test
  void aBodyDeclaredOverTheDefaultBoundIsRefusedUnreadAndOneDeclaredAtItIsQueued() {
    var max = RequestLimits.defaults().maxRequestBytes();
    var server = harness.server(harness.registry());
    var sessionId = harness.create(server, "alice");
    var overBound = new HeldBody(textBody(max + 1), new CountDownLatch(1), new CountDownLatch(0));

    var refused =
        Await.value(
            "the declared over-bound body to be refused",
            harness.upload(
                server,
                messages(sessionId),
                "alice",
                BodyPublishers.fromPublisher(
                    BodyPublishers.ofInputStream(() -> overBound), max + 1)));
    var atBound =
        harness.send(
            server,
            "POST",
            messages(sessionId),
            "alice",
            BodyPublishers.ofByteArray(textBody(max)));

    assertEquals(413, refused.statusCode());
    assertEquals(HELIDONS_EMPTY_BODY, refused.body());
    assertEquals(Optional.empty(), refused.headers().firstValue("Retry-After"));
    assertEquals(1, overBound.reading.getCount());
    assertEquals(202, atBound.statusCode());
    assertEquals(1, harness.sessions.get(sessionId).queue.size());
  }

  @Test
  void aPrincipalWithEveryRequestInFlightIsRefused429BeforeAnyWorkAndOthersAreNot() {
    var server = harness.server(harness.registry(), 2);
    var sessionId = harness.create(server, "alice");
    var session = harness.sessions.get(sessionId);
    harness.openEventsAdmitted(server, sessionId, "alice");
    harness.openEventsAdmitted(server, sessionId, "alice");
    var factoryRuns = harness.factoryRuns.get();

    var stream =
        Await.value(
            "the third stream to be answered", harness.openEvents(server, sessionId, "alice"));
    var message = harness.send(server, "POST", messages(sessionId), "alice");
    var create = harness.send(server, "POST", "/sessions", "alice");
    var bobs = harness.send(server, "POST", "/sessions", "bob");

    assertEquals(429, stream.statusCode());
    assertEquals(
        Optional.of(Overload.RETRY_AFTER_SECONDS), stream.headers().firstValue("Retry-After"));
    assertEquals(TOO_MANY_REQUESTS, ResponseBodies.text(stream.body()));
    for (var refused : List.of(message, create)) {
      assertEquals(429, refused.statusCode());
      assertRefused(refused, TOO_MANY_REQUESTS);
    }
    assertEquals(0, session.queue.size());
    assertEquals(2, session.subscribers.size());
    assertEquals(factoryRuns + 1, harness.factoryRuns.get());
    assertEquals(201, bobs.statusCode());

    session.subscribers.getFirst().onComplete();

    assertEquals(
        202, harness.sendAdmitted(server, "POST", messages(sessionId), "alice").statusCode());
    assertEquals(1, session.queue.size());
  }

  @Test
  void anOpenStreamAndAWaitingPollShareTheSlotsOfEveryRoute() {
    var server = harness.server(harness.registry(), 2);
    var sessionId = harness.create(server, "alice");
    var session = harness.sessions.get(sessionId);
    var poll = waitingPoll(server, sessionId);
    harness.openEventsAdmitted(server, sessionId, "alice");

    var answers =
        List.of(
            harness.send(server, "POST", "/sessions", "alice"),
            harness.send(server, "POST", messages(sessionId), "alice"),
            harness.send(server, "POST", interrupt(sessionId), "alice"),
            harness.send(server, "GET", events(sessionId), "alice"),
            harness.send(server, "GET", result(sessionId), "alice"),
            harness.send(server, "DELETE", "/sessions/" + sessionId, "alice"));

    for (var refused : answers) {
      assertEquals(429, refused.statusCode());
      assertRefused(refused, TOO_MANY_REQUESTS);
    }
    assertEquals(0, session.queue.size());
    assertEquals(0, session.closes.get());
    assertEquals(1, harness.sessions.size());
    session.terminate();
    assertEquals(200, Await.value("the waiting poll to be answered", poll).statusCode());
  }

  @Test
  void aRequestGivesItsSlotBackWhateverItIsAnswered() {
    var server = harness.server(harness.registry(), new RequestLimits(64, 1));
    var sessionId = harness.create(server, "alice");
    var path = messages(sessionId);
    harness.duringFactory.set(
        () -> {
          throw new IllegalStateException("options store down");
        });
    int factoryFailed;
    try (var ignored = LogCapture.of(AgentHttpService.class)) {
      factoryFailed = harness.sendAdmitted(server, "POST", "/sessions", "alice").statusCode();
    }
    harness.duringFactory.set(() -> {});

    var statuses =
        List.of(
            factoryFailed,
            harness.sendAdmitted(server, "GET", result("missing"), "alice").statusCode(),
            post(server, path, textBody(65)),
            post(server, path, "not json".getBytes(StandardCharsets.UTF_8)),
            post(server, path, "{\"text\":\" \"}".getBytes(StandardCharsets.UTF_8)),
            post(server, path, AdmissionHarness.BODY),
            post(server, path, AdmissionHarness.BODY),
            post(server, path, AdmissionHarness.BODY),
            harness
                .sendAdmitted(
                    server, "GET", "/sessions/" + sessionId + "/result?timeout=0", "alice")
                .statusCode(),
            harness.sendAdmitted(server, "POST", "/sessions", "alice").statusCode());

    assertEquals(List.of(500, 404, 413, 400, 400, 202, 202, 429, 204, 201), statuses);
  }

  @Test
  void aFailedRequestHoldsItsSlotUntilItsFailureIsAnswered() {
    var server =
        harness.server(
            SessionRegistry.newBuilder()
                .withFactory(
                    opts -> {
                      throw new IllegalStateException("session store down");
                    }),
            AdmissionHarness.limits(1));
    CompletableFuture<HttpResponse<String>> failed;
    HttpResponse<String> whileAnswering;
    try (var held = new HeldLog()) {
      failed = harness.sendAsync(server, "POST", "/sessions", "alice");
      Await.latch("the failure to be logged", held.logging);

      whileAnswering = harness.send(server, "GET", result("missing"), "alice");
    }
    assertEquals(500, Await.value("the failure to be answered", failed).statusCode());

    assertEquals(429, whileAnswering.statusCode());
    assertRefused(whileAnswering, TOO_MANY_REQUESTS);
    assertEquals(404, harness.sendAdmitted(server, "GET", result("missing"), "alice").statusCode());
  }

  @Test
  void aChunkedBodyIsRefused413AsSoonAsOneBytePastTheBoundArrives() throws IOException {
    var max = 32;
    var server = harness.server(harness.registry(), new RequestLimits(max, 32));
    var sessionId = harness.create(server, "alice");

    try (var connection = RawConnection.open(server.port())) {
      connection.write(
          "POST /v1"
              + messages(sessionId)
              + " HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer alice\r\n"
              + "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n"
              + Integer.toHexString(max + 1)
              + "\r\n");
      connection.write(textBody(max + 1));

      var refused = connection.response();

      assertEquals("HTTP/1.1 413 Request Entity Too Large", refused.statusLine());
      assertEquals("{\"error\":\"request body too large\"}", refused.body());
    }
    assertEquals(0, harness.sessions.get(sessionId).queue.size());
  }

  @Test
  void aResultPollThatFailsGivesItsSlotBack() {
    var server = harness.server(harness.registry(), AdmissionHarness.limits(1));
    var sessionId = harness.create(server, "alice");
    harness.sessions.get(sessionId).resultFailure = new IllegalStateException("result failed");

    var statuses =
        List.of(
            harness.sendAdmitted(server, "GET", result(sessionId), "alice").statusCode(),
            harness.sendAdmitted(server, "GET", result(sessionId), "alice").statusCode());

    assertEquals(List.of(500, 500), statuses);
  }

  @Test
  void aBurstOfMessagesFillsTheSteeringQueueAndIsRefused429Beyond() {
    var server = harness.server(harness.registry());
    var sessionId = harness.create(server, "alice");

    var sent = new ArrayList<CompletableFuture<HttpResponse<String>>>();
    for (var i = 0; i < BURST; i++) {
      sent.add(harness.sendAsync(server, "POST", messages(sessionId), "alice"));
    }
    var statuses =
        sent.stream().map(f -> Await.value("a message to be answered", f).statusCode()).toList();
    var interrupt = harness.send(server, "POST", interrupt(sessionId), "alice");

    assertEquals(2, statuses.stream().filter(status -> status == 202).count());
    assertEquals(BURST - 2, statuses.stream().filter(status -> status == 429).count());
    assertRefused(interrupt, "{\"error\":\"session message queue full\"}");
    assertEquals(2, harness.sessions.get(sessionId).queue.size());
  }

  /** Send a result poll of alice and return once it waits on the session's result. */
  private CompletableFuture<HttpResponse<String>> waitingPoll(
      RuntimeServer server, String sessionId) {
    var result = harness.sessions.get(sessionId).result;
    var watchers = result.getNumberOfDependents();
    var poll = harness.sendAsync(server, "GET", result(sessionId), "alice");
    Await.until(
        "the poll to wait on the result", () -> result.getNumberOfDependents() == watchers + 1);
    return poll;
  }

  private int post(RuntimeServer server, String path, byte[] body) {
    return harness
        .sendAdmitted(server, "POST", path, "alice", BodyPublishers.ofByteArray(body))
        .statusCode();
  }

  private static BodyPublisher chunked(byte[] body) {
    return BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body));
  }

  /** Holds {@link AgentHttpService}'s failure log record, so its answer waits, until closed. */
  private static final class HeldLog extends Handler implements AutoCloseable {

    private final Logger logger = Logger.getLogger(AgentHttpService.class.getName());
    private final CountDownLatch logging = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private HeldLog() {
      logger.addHandler(this);
    }

    @Override
    public void publish(LogRecord logRecord) {
      logging.countDown();
      Await.latch("the held failure to be released", release);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {
      release.countDown();
      logger.removeHandler(this);
    }
  }

  /** A request body that marks its first read, then serves its bytes once released. */
  private static final class HeldBody extends InputStream {

    private final CountDownLatch reading;
    private final CountDownLatch release;
    private final InputStream bytes;

    private HeldBody(byte[] bytes, CountDownLatch reading, CountDownLatch release) {
      this.bytes = new ByteArrayInputStream(bytes);
      this.reading = reading;
      this.release = release;
    }

    @Override
    public int read() throws IOException {
      hold();
      return bytes.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      hold();
      return bytes.read(buffer, offset, length);
    }

    private void hold() throws IOException {
      reading.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(e);
      }
    }
  }
}
