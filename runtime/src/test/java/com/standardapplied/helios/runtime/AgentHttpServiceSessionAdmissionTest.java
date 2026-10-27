/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static com.standardapplied.helios.runtime.AdmissionHarness.BURST;
import static com.standardapplied.helios.runtime.AdmissionHarness.assertRefused;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.test.Await;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /sessions} reserves capacity before the options factory runs, answers a refusal with
 * a fixed {@code 429} or {@code 503} that says nothing about other tenants, and never leaks or
 * double-releases capacity.
 */
final class AgentHttpServiceSessionAdmissionTest {

  private static final String SERVER_AT_CAPACITY = "{\"error\":\"server at session capacity\"}";

  private final AdmissionHarness harness = new AdmissionHarness();

  @AfterEach
  void closeHarness() {
    harness.close();
  }

  @Test
  void concurrentCreatesBeyondTheCapAreRefusedBeforeTheOptionsFactoryRuns() {
    var server = harness.server(harness.registry().withMaxSessions(2));
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    harness.duringFactory.set(
        () -> {
          entered.countDown();
          Await.latch("the test to release the factory", release);
        });
    var refused = new CountDownLatch(BURST - 2);

    var creates = new ArrayList<CompletableFuture<HttpResponse<String>>>();
    for (var i = 0; i < BURST; i++) {
      var create = harness.sendAsync(server, "POST", "/sessions", "alice-" + i);
      create.thenAccept(
          resp -> {
            if (resp.statusCode() == 503) {
              refused.countDown();
            }
          });
      creates.add(create);
    }
    Await.latch("every create beyond the cap to be refused", refused);
    Await.latch("the admitted creates to run the options factory", entered);
    assertEquals(2, harness.factoryRuns.get());
    release.countDown();

    var statuses = new ArrayList<Integer>();
    for (var create : creates) {
      var resp = Await.value("a create to be answered", create);
      statuses.add(resp.statusCode());
      if (resp.statusCode() == 503) {
        assertRefused(resp, SERVER_AT_CAPACITY);
      }
    }
    assertEquals(2, statuses.stream().filter(status -> status == 201).count());
    assertEquals(BURST - 2, statuses.stream().filter(status -> status == 503).count());
    assertEquals(2, harness.factoryRuns.get());
    assertEquals(2, harness.sessions.size());
  }

  @Test
  void aPrincipalAtItsOwnCapIsAnswered429AndLeavesTheRestToOthers() {
    var server = harness.server(harness.registry().withMaxSessionsPerPrincipal(1));
    harness.createRunning(server, "alice");

    var refused = harness.send(server, "POST", "/sessions", "alice");
    harness.create(server, "eve");

    assertEquals(429, refused.statusCode());
    assertRefused(refused, "{\"error\":\"session quota reached\"}");
    assertEquals(2, harness.factoryRuns.get());
  }

  @Test
  void aPrincipalAtItsCapIsAnsweredTheSameWhenAnotherFillsTheServersCap() {
    var server =
        harness.server(harness.registry().withMaxSessionsPerPrincipal(1).withMaxSessions(2));
    harness.createRunning(server, "alice");

    var alone = harness.send(server, "POST", "/sessions", "alice");
    harness.create(server, "eve");
    var crowded = harness.send(server, "POST", "/sessions", "alice");

    assertEquals(429, alone.statusCode());
    assertRefused(alone, "{\"error\":\"session quota reached\"}");
    assertEquals(alone.statusCode(), crowded.statusCode());
    assertEquals(alone.body(), crowded.body());
    for (var header : List.of("Content-Type", "Content-Length", "Retry-After")) {
      assertEquals(alone.headers().allValues(header), crowded.headers().allValues(header), header);
    }
    assertEquals(2, harness.factoryRuns.get());
  }

  @Test
  void aCreateRefusedAmongAnotherTenantsSessionsIsAnsweredAsIfTheyHeldNone() {
    var crowded = harness.server(harness.registry().withMaxSessions(2));
    var oldest = harness.sessions.get(harness.create(crowded, "alice"));
    oldest.terminate();
    var younger = harness.sessions.get(harness.create(crowded, "alice"));
    younger.terminate();
    var ownOnly = harness.server(harness.registry().withMaxSessions(2));
    harness.createRunning(ownOnly, "eve");
    harness.createRunning(ownOnly, "eve");

    var amongOthers = harness.send(crowded, "POST", "/sessions", "eve");
    var amongOwn = harness.send(ownOnly, "POST", "/sessions", "eve");

    assertEquals(amongOwn.statusCode(), amongOthers.statusCode());
    assertEquals(amongOwn.body(), amongOthers.body());
    for (var header : List.of("Content-Type", "Content-Length", "Retry-After")) {
      assertEquals(
          amongOwn.headers().allValues(header), amongOthers.headers().allValues(header), header);
    }
    assertRefused(amongOthers, SERVER_AT_CAPACITY);
    assertEquals(0, oldest.closes.get() + younger.closes.get());

    assertEquals(201, harness.send(crowded, "POST", "/sessions", "alice").statusCode());
    assertEquals(1, oldest.closes.get());
    assertEquals(0, younger.closes.get());
  }

  @Test
  void aFailedCreateAndARepeatedDeleteLeaveTheCapacityAsItWas() {
    var server = harness.server(harness.registry().withMaxSessions(1));
    harness.duringFactory.set(
        () -> {
          throw new IllegalStateException("options store down");
        });
    try (var ignored = LogCapture.of(AgentHttpService.class)) {
      assertEquals(500, harness.send(server, "POST", "/sessions", "alice").statusCode());
    }
    harness.duringFactory.set(() -> {});

    var sessionId = harness.create(server, "alice");
    var path = "/sessions/" + sessionId;
    assertEquals(204, harness.send(server, "DELETE", path, "alice").statusCode());
    assertEquals(404, harness.send(server, "DELETE", path, "alice").statusCode());

    harness.createRunning(server, "alice");
    assertEquals(503, harness.send(server, "POST", "/sessions", "eve").statusCode());
  }
}
