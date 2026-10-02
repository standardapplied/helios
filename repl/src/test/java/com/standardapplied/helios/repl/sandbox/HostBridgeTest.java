/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.repl.protocol.RpcMessage;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Each delegation test calls the bridge on its own thread, as sandbox code would, takes the request
 * the bootstrap wrote for the host, answers it, and asserts what the bridge returned.
 */
class HostBridgeTest {

  @Test
  void predictWithNoBootstrapThrows() {
    assertThrows(IllegalStateException.class, () -> HostBridge.predict("instructions", "input"));
  }

  @Test
  void submitWithNoBootstrapThrows() {
    assertThrows(IllegalStateException.class, () -> HostBridge.submit("value"));
  }

  @Test
  void fetchWithNoBootstrapThrows() {
    assertThrows(IllegalStateException.class, () -> HostBridge.fetch("https://example.com"));
  }

  @Test
  void queryWithNoBootstrapThrows() {
    assertThrows(IllegalStateException.class, () -> HostBridge.query("SELECT 1"));
  }

  @Test
  void predictDelegatesToBootstrap() {
    try (var env = BootstrapEnvironment.reading()) {
      var result = env.inSandbox(() -> HostBridge.predict("Be concise", "2+2?"));

      var request = answer(env, Map.of("output", "4"));

      assertEquals("predict", request.method());
      assertEquals("4", Await.value("predict's result", result));
    }
  }

  @Test
  void predictWithNonMapResult() {
    try (var env = BootstrapEnvironment.reading()) {
      var result = env.inSandbox(() -> HostBridge.predict("instruct", "input"));

      answer(env, "plain");

      assertEquals("plain", Await.value("predict's result", result));
    }
  }

  @Test
  void predictWithNullOutputInMap() {
    try (var env = BootstrapEnvironment.reading()) {
      var result = env.inSandbox(() -> HostBridge.predict("instruct", "input"));
      var nullOutput = new HashMap<String, Object>();
      nullOutput.put("output", null);

      answer(env, nullOutput);

      assertEquals("", Await.value("predict's result", result));
    }
  }

  @Test
  void predictWithNullResult() {
    try (var env = BootstrapEnvironment.reading()) {
      var result = env.inSandbox(() -> HostBridge.predict("instruct", "input"));

      answer(env, null);

      assertEquals("", Await.value("predict's result", result));
    }
  }

  @Test
  void submitDelegatesToBootstrap() {
    try (var env = BootstrapEnvironment.reading()) {
      var submitted = submitInSandbox(env, "answer");

      var request = answer(env, Map.of("status", "accepted"));

      assertEquals("submit", request.method());
      Await.value("submit to return", submitted);
    }
  }

  @Test
  void submitStoresValue() {
    try (var env = BootstrapEnvironment.reading()) {
      var submitted = submitInSandbox(env, "stored-value");

      answer(env, Map.of("status", "accepted"));

      Await.value("submit to return", submitted);
      assertEquals("stored-value", env.bootstrap().submittedValue());
    }
  }

  @Test
  void fetchDelegatesToBootstrap() {
    try (var env = BootstrapEnvironment.reading()) {
      var fetched = env.inSandbox(() -> HostBridge.fetch("https://api.example.com/x"));

      var request =
          answer(env, Map.of("status", 200, "body", "payload", "contentType", "application/json"));

      assertEquals("fetch", request.method());
      assertEquals("https://api.example.com/x", ((Map<?, ?>) request.params()).get("url"));
      var result = Await.value("fetch's result", fetched);
      assertEquals(200, result.get("status"));
      assertEquals("payload", result.get("body"));
      assertEquals("application/json", result.get("contentType"));
    }
  }

  @Test
  void fetchToleratesNullValues() {
    try (var env = BootstrapEnvironment.reading()) {
      var fetched = env.inSandbox(() -> HostBridge.fetch("https://api.example.com/x"));
      var mapWithNull = new LinkedHashMap<String, Object>();
      mapWithNull.put("status", 204);
      mapWithNull.put("body", null);
      mapWithNull.put("contentType", null);

      answer(env, mapWithNull);

      var result = Await.value("fetch's result", fetched);
      assertEquals(204, result.get("status"));
      assertNull(result.get("body"));
      assertNull(result.get("contentType"));
    }
  }

  @Test
  void fetchResultIsUnmodifiable() {
    try (var env = BootstrapEnvironment.reading()) {
      var fetched = env.inSandbox(() -> HostBridge.fetch("https://api.example.com/x"));

      answer(env, Map.of("status", 200, "body", "x"));

      var result = Await.value("fetch's result", fetched);
      assertThrows(UnsupportedOperationException.class, () -> result.put("evil", "yes"));
    }
  }

  @Test
  void fetchWithNonMapResultReturnsEmpty() {
    try (var env = BootstrapEnvironment.reading()) {
      var fetched = env.inSandbox(() -> HostBridge.fetch("https://api.example.com/x"));

      answer(env, "not-a-map");

      assertTrue(Await.value("fetch's result", fetched).isEmpty());
    }
  }

  @Test
  void queryDelegatesToBootstrap() {
    try (var env = BootstrapEnvironment.reading()) {
      var queried = env.inSandbox(() -> HostBridge.query("SELECT region, total FROM sales"));

      var request =
          answer(
              env,
              List.of(Map.of("region", "US", "total", 100), Map.of("region", "EU", "total", 50)));

      assertEquals("query", request.method());
      assertEquals("SELECT region, total FROM sales", ((Map<?, ?>) request.params()).get("sql"));
      var rows = Await.value("query's rows", queried);
      assertEquals(2, rows.size());
      assertEquals("US", rows.get(0).get("region"));
      assertEquals(50, rows.get(1).get("total"));
    }
  }

  @Test
  void queryRowsToleratesNullColumnValues() {
    try (var env = BootstrapEnvironment.reading()) {
      var queried = env.inSandbox(() -> HostBridge.query("SELECT name, age FROM users"));
      var rowWithNull = new LinkedHashMap<String, Object>();
      rowWithNull.put("name", "alice");
      rowWithNull.put("age", null);

      answer(env, List.of(rowWithNull));

      var rows = Await.value("query's rows", queried);
      assertEquals(1, rows.size());
      assertEquals("alice", rows.get(0).get("name"));
      assertNull(rows.get(0).get("age"));
    }
  }

  @Test
  void queryRowsAreUnmodifiable() {
    try (var env = BootstrapEnvironment.reading()) {
      var queried = env.inSandbox(() -> HostBridge.query("SELECT 1"));

      answer(env, List.of(Map.of("x", 1)));

      var rows = Await.value("query's rows", queried);
      assertThrows(UnsupportedOperationException.class, () -> rows.add(Map.of()));
      assertThrows(UnsupportedOperationException.class, () -> rows.get(0).put("y", 2));
    }
  }

  @Test
  void queryWithNonListResultReturnsEmpty() {
    try (var env = BootstrapEnvironment.reading()) {
      var queried = env.inSandbox(() -> HostBridge.query("SELECT 1"));

      answer(env, "scalar-result");

      assertTrue(Await.value("query's rows", queried).isEmpty());
    }
  }

  @Test
  void queryWithEmptyResultListReturnsEmpty() {
    try (var env = BootstrapEnvironment.reading()) {
      var queried = env.inSandbox(() -> HostBridge.query("SELECT * FROM empty"));

      answer(env, List.of());

      assertTrue(Await.value("query's rows", queried).isEmpty());
    }
  }

  private static CompletableFuture<Void> submitInSandbox(BootstrapEnvironment env, Object value) {
    return env.inSandbox(
        () -> {
          HostBridge.submit(value);
          return null;
        });
  }

  /** Takes the request the bridge call sent to the host and answers it with {@code result}. */
  private static RpcMessage.Request answer(BootstrapEnvironment env, Object result) {
    var request = env.nextRequest();
    env.feed(new RpcMessage.Response(request.id(), result));
    return request;
  }
}
