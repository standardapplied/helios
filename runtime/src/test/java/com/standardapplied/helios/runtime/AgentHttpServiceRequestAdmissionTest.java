/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static com.standardapplied.helios.runtime.AdmissionHarness.BURST;
import static com.standardapplied.helios.runtime.AdmissionHarness.assertRefused;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Message bodies, steering-queue entries, event streams and result polls are each admitted against
 * their bound before any work, and a burst beyond the bound leaves exactly the bound held and the
 * counters reporting what was admitted and refused.
 */
final class AgentHttpServiceRequestAdmissionTest {

  private static final String TOO_LARGE = "{\"error\":\"request body too large\"}";

  private final AdmissionHarness harness = new AdmissionHarness();

  @AfterEach
  void closeHarness() {
    harness.close();
  }

  @Test
  void aBodyOverTheBoundIsAnswered413BeforeAnythingIsQueued() {
    var max = 32;
    var server =
        harness.server(
            harness.registry(), RequestLimits.newBuilder().withMaxRequestBytes(max).build());
    var sessionId = harness.create(server, "alice");
    var overBound = textBody(max + 1);

    var declared =
        harness.send(
            server, "POST", messages(sessionId), "alice", BodyPublishers.ofByteArray(overBound));
    var chunked = harness.send(server, "POST", messages(sessionId), "alice", chunked(overBound));
    var interrupt =
        harness.send(
            server, "POST", interrupt(sessionId), "alice", BodyPublishers.ofByteArray(overBound));

    for (var resp : List.of(declared, chunked, interrupt)) {
      assertEquals(413, resp.statusCode());
      assertEquals(TOO_LARGE, resp.body());
    }
    var session = harness.sessions.get(sessionId);
    assertEquals(0, session.queue.size());
    var atBound =
        harness.send(server, "POST", messages(sessionId), "alice", chunked(textBody(max)));
    assertEquals(202, atBound.statusCode());
    assertEquals(1, session.queue.size());
  }

  @Test
  void uploadsBeyondThePrincipalsBoundAre429AndBeyondTheServicesAre503BeforeTheirBodyIsRead() {
    var server =
        harness.server(
            harness.registry(),
            RequestLimits.newBuilder()
                .withMaxMessageUploads(3)
                .withMaxMessageUploadsPerPrincipal(2)
                .build());
    var alice = harness.create(server, "alice");
    var bob = harness.create(server, "bob");
    var release = new CountDownLatch(1);
    var aliceReading = new CountDownLatch(2);
    var bobReading = new CountDownLatch(1);
    var held =
        List.of(
            harness.upload(server, messages(alice), "alice", new HeldBody(aliceReading, release)),
            harness.upload(server, interrupt(alice), "alice", new HeldBody(aliceReading, release)));
    Await.latch("alice's two uploads to be read", aliceReading);

    var aliceUnread = new HeldBody(new CountDownLatch(1), release);
    var aliceRefused =
        Await.value(
            "alice's third upload to be refused",
            harness.upload(server, messages(alice), "alice", aliceUnread));
    var bobHeld = harness.upload(server, messages(bob), "bob", new HeldBody(bobReading, release));
    Await.latch("bob's upload to be read", bobReading);
    var bobUnread = new HeldBody(new CountDownLatch(1), release);
    var bobRefused =
        Await.value(
            "bob's second upload to be refused",
            harness.upload(server, messages(bob), "bob", bobUnread));

    assertEquals(429, aliceRefused.statusCode());
    assertRefused(aliceRefused, "{\"error\":\"too many message uploads in progress\"}");
    assertEquals(503, bobRefused.statusCode());
    assertRefused(bobRefused, "{\"error\":\"server at message upload capacity\"}");
    assertEquals(1, aliceUnread.reading.getCount());
    assertEquals(1, bobUnread.reading.getCount());
    assertEquals(0, harness.sessions.get(alice).queue.size());

    release.countDown();
    for (var upload : List.of(held.get(0), held.get(1), bobHeld)) {
      assertEquals(202, Await.value("a held upload to be queued", upload).statusCode());
    }
    assertEquals(2, harness.sessions.get(alice).queue.size());
    assertEquals(202, harness.sendAdmitted(server, "POST", messages(bob), "bob").statusCode());
    assertEquals(new AdmissionCounts(4, 2), server.counters().messages());
  }

  @Test
  void anUploadGivesItsAdmissionBackWhateverItIsAnswered() {
    var server =
        harness.server(
            harness.registry(),
            RequestLimits.newBuilder()
                .withMaxRequestBytes(64)
                .withMaxMessageUploads(1)
                .withMaxMessageUploadsPerPrincipal(1)
                .build());
    var sessionId = harness.create(server, "alice");
    var path = messages(sessionId);

    var statuses =
        List.of(
            post(server, path, textBody(65)),
            post(server, path, "not json".getBytes(StandardCharsets.UTF_8)),
            post(server, path, "{\"text\":\" \"}".getBytes(StandardCharsets.UTF_8)),
            post(server, path, AdmissionHarness.BODY),
            post(server, path, AdmissionHarness.BODY),
            post(server, path, AdmissionHarness.BODY),
            post(server, messages(harness.create(server, "alice")), AdmissionHarness.BODY));

    assertEquals(List.of(413, 400, 400, 202, 202, 429, 202), statuses);
  }

  @Test
  void aBurstOfMessagesFillsTheSteeringQueueAndIsRefused429Beyond() {
    var server = harness.server(harness.registry(), RequestLimits.defaults());
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
    assertEquals(new AdmissionCounts(2, BURST - 1), server.counters().messages());
  }

  @Test
  void aBurstOfEventStreamsOpensUpToTheSessionsLimitAndFreesASlotWhenOneEnds() throws Exception {
    var server = harness.server(harness.registry(), limits());
    var sessionId = harness.create(server, "alice");
    var session = harness.sessions.get(sessionId);

    var streams = new ArrayList<CompletableFuture<HttpResponse<InputStream>>>();
    for (var i = 0; i < BURST; i++) {
      streams.add(harness.openEvents(server, sessionId, "alice"));
    }
    var open = new ArrayList<HttpResponse<InputStream>>();
    for (var stream : streams) {
      var resp = Await.value("an event stream to be answered", stream);
      if (resp.statusCode() == 200) {
        open.add(resp);
      } else {
        assertEquals(429, resp.statusCode());
        assertEquals(
            Optional.of(Overload.RETRY_AFTER_SECONDS), resp.headers().firstValue("Retry-After"));
        assertEquals(
            "{\"error\":\"too many event streams on this session\"}",
            ResponseBodies.text(resp.body()));
      }
    }

    assertEquals(2, open.size());
    assertEquals(2, session.subscribers.size());
    assertEquals(new AdmissionCounts(2, BURST - 2), server.counters().eventStreams());

    session.completeEvents();
    for (var resp : open) {
      Await.value(
          "the stream to end",
          CompletableFuture.supplyAsync(() -> ResponseBodies.text(resp.body())));
    }
    harness.openEventsAdmitted(server, sessionId, "alice").body().close();
  }

  @Test
  void aBurstOfResultPollsWaitsUpToTheSessionsLimitAndFreesASlotWhenAnswered() {
    var server = harness.server(harness.registry(), limits());
    var sessionId = harness.create(server, "alice");
    var session = harness.sessions.get(sessionId);
    var watchers = session.result.getNumberOfDependents();
    var refused = new CountDownLatch(BURST - 2);

    var polls = new ArrayList<CompletableFuture<HttpResponse<String>>>();
    for (var i = 0; i < BURST; i++) {
      var poll = harness.sendAsync(server, "GET", result(sessionId), "alice");
      poll.thenAccept(
          resp -> {
            if (resp.statusCode() == 429) {
              refused.countDown();
            }
          });
      polls.add(poll);
    }
    Await.latch("every poll beyond the limit to be refused", refused);
    Await.until(
        "the admitted polls to wait on the result",
        () -> session.result.getNumberOfDependents() == watchers + 2);
    assertEquals(new AdmissionCounts(2, BURST - 2), server.counters().resultPolls());

    session.terminate();

    var answered = 0;
    for (var poll : polls) {
      var resp = Await.value("a poll to be answered", poll);
      if (resp.statusCode() == 200) {
        answered++;
      } else {
        assertRefused(resp, "{\"error\":\"too many result polls on this session\"}");
      }
    }
    assertEquals(2, answered);
    assertEquals(200, harness.sendAdmitted(server, "GET", result(sessionId), "alice").statusCode());
  }

  private int post(RuntimeServer server, String path, byte[] body) {
    return harness
        .sendAdmitted(server, "POST", path, "alice", BodyPublishers.ofByteArray(body))
        .statusCode();
  }

  private static RequestLimits limits() {
    return RequestLimits.newBuilder()
        .withMaxEventStreamsPerSession(2)
        .withMaxResultPollsPerSession(2)
        .build();
  }

  private static BodyPublisher chunked(byte[] body) {
    return BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body));
  }

  /** A request body that marks its first read, then serves its bytes once released. */
  private static final class HeldBody extends InputStream {

    private final CountDownLatch reading;
    private final CountDownLatch release;
    private final InputStream bytes = new ByteArrayInputStream(AdmissionHarness.BODY);

    private HeldBody(CountDownLatch reading, CountDownLatch release) {
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
