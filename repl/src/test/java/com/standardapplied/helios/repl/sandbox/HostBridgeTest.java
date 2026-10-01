/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.protocol.ProcessTransport;
import ai.singlr.repl.protocol.RpcMessage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import jdk.jshell.JShell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HostBridgeTest {

  @AfterEach
  void tearDown() {
    JvmSandboxBootstrap.setInstance(null);
  }

  @Test
  void predictWithNoBootstrapThrows() {
    assertThrows(IllegalStateException.class, () -> HostBridge.predict("instructions", "input"));
  }

  @Test
  void submitWithNoBootstrapThrows() {
    assertThrows(IllegalStateException.class, () -> HostBridge.submit("value"));
  }

  @Test
  void predictDelegatesToBootstrap() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture = new CompletableFuture<String>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  resultFuture.complete(HostBridge.predict("Be concise", "2+2?"));
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      assertTrue(line.startsWith(ProcessTransport.RPC_PREFIX));
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      assertInstanceOf(RpcMessage.Request.class, msg);
      var req = (RpcMessage.Request) msg;
      assertEquals("predict", req.method());

      env.writeLine(
          ProcessTransport.serializeMessage(
              new RpcMessage.Response(req.id(), Map.of("output", "4"))));

      assertEquals("4", resultFuture.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void predictWithNonMapResult() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture = new CompletableFuture<String>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  resultFuture.complete(HostBridge.predict("instruct", "input"));
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;

      env.writeLine(ProcessTransport.serializeMessage(new RpcMessage.Response(req.id(), "plain")));

      assertEquals("plain", resultFuture.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void submitDelegatesToBootstrap() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture = new CompletableFuture<Void>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  HostBridge.submit("answer");
                  resultFuture.complete(null);
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;
      assertEquals("submit", req.method());

      env.writeLine(
          ProcessTransport.serializeMessage(
              new RpcMessage.Response(req.id(), Map.of("status", "accepted"))));

      resultFuture.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void submitStoresValue() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture = new CompletableFuture<Void>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  HostBridge.submit("stored-value");
                  resultFuture.complete(null);
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;

      env.writeLine(
          ProcessTransport.serializeMessage(
              new RpcMessage.Response(req.id(), Map.of("status", "accepted"))));

      resultFuture.get(5, TimeUnit.SECONDS);

      assertEquals("stored-value", env.bootstrap.submittedValue());
    }
  }

  @Test
  void predictWithNullOutputInMap() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture = new CompletableFuture<String>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  resultFuture.complete(HostBridge.predict("instruct", "input"));
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;

      env.writeLine(
          ProcessTransport.serializeMessage(
              new RpcMessage.Response(
                  req.id(),
                  new java.util.HashMap<String, Object>() {
                    {
                      put("output", null);
                    }
                  })));

      assertEquals("", resultFuture.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void predictWithNullResult() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture = new CompletableFuture<String>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  resultFuture.complete(HostBridge.predict("instruct", "input"));
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;

      env.writeLine(ProcessTransport.serializeMessage(new RpcMessage.Response(req.id(), null)));

      assertEquals("", resultFuture.get(5, TimeUnit.SECONDS));
    }
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
  void fetchDelegatesToBootstrap() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture = new CompletableFuture<Map<String, Object>>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  resultFuture.complete(HostBridge.fetch("https://api.example.com/x"));
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      assertTrue(line.startsWith(ProcessTransport.RPC_PREFIX));
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      assertInstanceOf(RpcMessage.Request.class, msg);
      var req = (RpcMessage.Request) msg;
      assertEquals("fetch", req.method());
      assertEquals("https://api.example.com/x", ((Map<?, ?>) req.params()).get("url"));

      env.writeLine(
          ProcessTransport.serializeMessage(
              new RpcMessage.Response(
                  req.id(),
                  Map.of("status", 200, "body", "payload", "contentType", "application/json"))));

      var result = resultFuture.get(5, TimeUnit.SECONDS);
      assertEquals(200, result.get("status"));
      assertEquals("payload", result.get("body"));
      assertEquals("application/json", result.get("contentType"));
    }
  }

  @Test
  void queryDelegatesToBootstrap() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture =
          new java.util.concurrent.CompletableFuture<java.util.List<Map<String, Object>>>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  resultFuture.complete(HostBridge.query("SELECT region, total FROM sales"));
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;
      assertEquals("query", req.method());
      assertEquals("SELECT region, total FROM sales", ((Map<?, ?>) req.params()).get("sql"));

      env.writeLine(
          ProcessTransport.serializeMessage(
              new RpcMessage.Response(
                  req.id(),
                  java.util.List.of(
                      Map.of("region", "US", "total", 100), Map.of("region", "EU", "total", 50)))));

      var rows = resultFuture.get(5, TimeUnit.SECONDS);
      assertEquals(2, rows.size());
      assertEquals("US", rows.get(0).get("region"));
      assertEquals(50, rows.get(1).get("total"));
    }
  }

  @Test
  void queryRowsToleratesNullColumnValues() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture =
          new java.util.concurrent.CompletableFuture<java.util.List<Map<String, Object>>>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  resultFuture.complete(HostBridge.query("SELECT name, age FROM users"));
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;

      var rowWithNull = new java.util.LinkedHashMap<String, Object>();
      rowWithNull.put("name", "alice");
      rowWithNull.put("age", null);
      env.writeLine(
          ProcessTransport.serializeMessage(
              new RpcMessage.Response(req.id(), java.util.List.of(rowWithNull))));

      var rows = resultFuture.get(5, TimeUnit.SECONDS);
      assertEquals(1, rows.size());
      assertEquals("alice", rows.get(0).get("name"));
      assertNull(rows.get(0).get("age"));
    }
  }

  @Test
  void fetchToleratesNullValues() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture = new CompletableFuture<Map<String, Object>>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  resultFuture.complete(HostBridge.fetch("https://api.example.com/x"));
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;

      var mapWithNull = new java.util.LinkedHashMap<String, Object>();
      mapWithNull.put("status", 204);
      mapWithNull.put("body", null);
      mapWithNull.put("contentType", null);
      env.writeLine(
          ProcessTransport.serializeMessage(new RpcMessage.Response(req.id(), mapWithNull)));

      var result = resultFuture.get(5, TimeUnit.SECONDS);
      assertEquals(204, result.get("status"));
      assertNull(result.get("body"));
      assertNull(result.get("contentType"));
    }
  }

  @Test
  void queryRowsAreUnmodifiable() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture =
          new java.util.concurrent.CompletableFuture<java.util.List<Map<String, Object>>>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  resultFuture.complete(HostBridge.query("SELECT 1"));
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;

      env.writeLine(
          ProcessTransport.serializeMessage(
              new RpcMessage.Response(req.id(), java.util.List.of(Map.of("x", 1)))));

      var rows = resultFuture.get(5, TimeUnit.SECONDS);
      assertThrows(UnsupportedOperationException.class, () -> rows.add(Map.of()));
      assertThrows(UnsupportedOperationException.class, () -> rows.get(0).put("y", 2));
    }
  }

  @Test
  void fetchResultIsUnmodifiable() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture = new CompletableFuture<Map<String, Object>>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  resultFuture.complete(HostBridge.fetch("https://api.example.com/x"));
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;

      env.writeLine(
          ProcessTransport.serializeMessage(
              new RpcMessage.Response(req.id(), Map.of("status", 200, "body", "x"))));

      var result = resultFuture.get(5, TimeUnit.SECONDS);
      assertThrows(UnsupportedOperationException.class, () -> result.put("evil", "yes"));
    }
  }

  @Test
  void fetchWithNonMapResultReturnsEmpty() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture = new CompletableFuture<Map<String, Object>>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  resultFuture.complete(HostBridge.fetch("https://api.example.com/x"));
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;

      env.writeLine(
          ProcessTransport.serializeMessage(new RpcMessage.Response(req.id(), "not-a-map")));

      assertTrue(resultFuture.get(5, TimeUnit.SECONDS).isEmpty());
    }
  }

  @Test
  void queryWithNonListResultReturnsEmpty() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture =
          new java.util.concurrent.CompletableFuture<java.util.List<Map<String, Object>>>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  resultFuture.complete(HostBridge.query("SELECT 1"));
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;

      env.writeLine(
          ProcessTransport.serializeMessage(new RpcMessage.Response(req.id(), "scalar-result")));

      assertTrue(resultFuture.get(5, TimeUnit.SECONDS).isEmpty());
    }
  }

  @Test
  void queryWithEmptyResultListReturnsEmpty() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture =
          new java.util.concurrent.CompletableFuture<java.util.List<Map<String, Object>>>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  resultFuture.complete(HostBridge.query("SELECT * FROM empty"));
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;

      env.writeLine(
          ProcessTransport.serializeMessage(
              new RpcMessage.Response(req.id(), java.util.List.of())));

      assertTrue(resultFuture.get(5, TimeUnit.SECONDS).isEmpty());
    }
  }

  private static class BootstrapEnv implements AutoCloseable {
    final JShell jshell;
    final JvmSandboxBootstrap bootstrap;
    final PipedOutputStream stdinWriter;
    final BufferedReader stdoutBufReader;
    final Thread readLoopThread;

    BootstrapEnv() throws Exception {
      jshell = JShell.builder().executionEngine("local").build();
      var targetClasses = java.nio.file.Path.of("target", "classes").toAbsolutePath();
      if (java.nio.file.Files.isDirectory(targetClasses)) {
        jshell.addToClasspath(targetClasses.toString());
      }
      stdinWriter = new PipedOutputStream();
      var stdinPipe = new PipedInputStream(stdinWriter);
      var stdoutPipe = new PipedOutputStream();
      var stdoutReader = new PipedInputStream(stdoutPipe);
      var realOut = new PrintStream(stdoutPipe, true, StandardCharsets.UTF_8);
      var stdinBufReader =
          new BufferedReader(new InputStreamReader(stdinPipe, StandardCharsets.UTF_8));
      bootstrap = new JvmSandboxBootstrap(jshell, stdinBufReader, realOut);
      JvmSandboxBootstrap.setInstance(bootstrap);
      stdoutBufReader =
          new BufferedReader(new InputStreamReader(stdoutReader, StandardCharsets.UTF_8));
      readLoopThread = Thread.ofVirtual().name("test-readloop").start(bootstrap::readLoop);
    }

    String readLine() throws Exception {
      return stdoutBufReader.readLine();
    }

    void writeLine(String json) throws Exception {
      stdinWriter.write((json + "\n").getBytes(StandardCharsets.UTF_8));
      stdinWriter.flush();
    }

    @Override
    public void close() throws Exception {
      stdinWriter.close();
      readLoopThread.join(Duration.ofSeconds(2));
      jshell.close();
    }
  }
}
