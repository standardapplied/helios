/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static com.standardapplied.helios.runtime.ServerStubs.disconnectedResponse;
import static com.standardapplied.helios.runtime.ServerStubs.stub;
import static com.standardapplied.helios.runtime.ServerStubs.untouchedResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.runtime.AuthenticationGate.Caller;
import io.helidon.webserver.CloseConnectionException;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * An upload refused by the principal's or the service's bound is counted as rejected even when the
 * refusal cannot be sent, and gives back no capacity it never held.
 */
final class SteeringInputTest {

  private static final Caller ALICE = caller(new Principal("acme", "alice"));
  private static final Caller BOB = caller(new Principal("acme", "bob"));

  private final StubSession session = new StubSession("s");
  private final ExecutorService uploads = Executors.newVirtualThreadPerTaskExecutor();
  private final List<CountDownLatch> clients = new CopyOnWriteArrayList<>();

  @AfterEach
  void close() {
    clients.forEach(CountDownLatch::countDown);
    uploads.close();
  }

  @Test
  void aPrincipalRefusalThatFailsToSendIsCountedAndReleasesNothing() {
    var steering = steering(1, 2);
    var held = new HeldUpload(steering, ALICE);

    assertThrows(
        CloseConnectionException.class,
        () -> steering.send(session, ALICE, untouchedRequest(), disconnectedResponse()));
    assertEquals(new AdmissionCounts(0, 1), steering.counts());
    steering.send(session, ALICE, untouchedRequest(), answeredResponse());
    assertEquals(new AdmissionCounts(0, 2), steering.counts());

    held.end();
    var again = new HeldUpload(steering, ALICE);
    steering.send(session, ALICE, untouchedRequest(), answeredResponse());
    assertEquals(new AdmissionCounts(0, 3), steering.counts());
    again.end();
  }

  @Test
  void aServiceRefusalThatFailsToSendIsCountedAndReleasesNothing() {
    var steering = steering(1, 1);
    var held = new HeldUpload(steering, ALICE);

    assertThrows(
        CloseConnectionException.class,
        () -> steering.interrupt(session, BOB, untouchedRequest(), disconnectedResponse()));
    assertEquals(new AdmissionCounts(0, 1), steering.counts());
    steering.interrupt(session, BOB, untouchedRequest(), answeredResponse());
    assertEquals(new AdmissionCounts(0, 2), steering.counts());

    held.end();
    var again = new HeldUpload(steering, ALICE);
    steering.interrupt(session, BOB, untouchedRequest(), answeredResponse());
    assertEquals(new AdmissionCounts(0, 3), steering.counts());
    again.end();
  }

  private static SteeringInput steering(int perPrincipal, int total) {
    return new SteeringInput(
        RequestLimits.newBuilder()
            .withMaxMessageUploadsPerPrincipal(perPrincipal)
            .withMaxMessageUploads(total)
            .build(),
        JsonMapper.builder().build());
  }

  private static Caller caller(Principal principal) {
    return new Caller(
        principal, new CompletableFuture<>(), new Challenge(TestAuthenticator.CHALLENGE));
  }

  /** A request a refused upload never reads. */
  private static ServerRequest untouchedRequest() {
    return stub(
        ServerRequest.class,
        name -> {
          throw new AssertionError("a refused upload was read: " + name);
        });
  }

  /** A response whose refusal is sent. */
  private static ServerResponse answeredResponse() {
    return stub(ServerResponse.class, name -> null);
  }

  /**
   * A message upload admitted and held while its body is read, until {@link #end()} drops the
   * client. Returns once admitted.
   */
  private final class HeldUpload {

    private final CountDownLatch dropped = new CountDownLatch(1);
    private final Future<?> upload;

    HeldUpload(SteeringInput steering, Caller caller) {
      clients.add(dropped);
      var reading = new CountDownLatch(1);
      var request =
          stub(
              ServerRequest.class,
              name -> {
                reading.countDown();
                Await.latch("the client to drop", dropped);
                throw new CloseConnectionException("peer closed");
              });
      upload = uploads.submit(() -> steering.send(session, caller, request, untouchedResponse()));
      Await.latch("the upload to be admitted", reading);
    }

    void end() {
      dropped.countDown();
      assertInstanceOf(
          CloseConnectionException.class, Await.failure("the held upload to end", upload));
    }
  }
}
