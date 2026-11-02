/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox;

import static com.standardapplied.helios.repl.protocol.ProcessTransport.MAX_FRAME_CHARS;
import static com.standardapplied.helios.repl.protocol.ProcessTransport.RPC_PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.repl.protocol.ProcessTransport;
import com.standardapplied.helios.repl.protocol.RpcMessage;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Each delegation test calls the bridge on its own thread, as sandbox code would, takes the request
 * the bootstrap wrote for the host, answers it, and asserts what the bridge returned.
 */
class HostBridgeTest {

  @Test
  void callWithNoBootstrapThrows() {
    var thrown =
        assertThrows(IllegalStateException.class, () -> HostBridge.__call("ping", Map.of()));
    assertEquals("HostBridge can only be called from within a sandbox", thrown.getMessage());
  }

  @Test
  void callForwardsTheNamedFunctionWithItsArguments() {
    try (var env = BootstrapEnvironment.reading()) {
      var called = env.inSandbox(() -> HostBridge.__call("marketQuote", Map.of("ticker", "AAPL")));

      var request = answer(env, 187.5);

      assertEquals("marketQuote", request.method());
      assertEquals(Map.of("ticker", "AAPL"), request.params());
      assertEquals(187.5, Await.value("the call's result", called));
    }
  }

  @Test
  void callWithoutArgumentsSendsAnEmptyObject() {
    try (var env = BootstrapEnvironment.reading()) {
      var called = env.inSandbox(() -> HostBridge.__call("ping", null));

      var request = answer(env, "pong");

      assertEquals("ping", request.method());
      assertEquals(Map.of(), request.params());
      assertEquals("pong", Await.value("the call's result", called));
    }
  }

  @Test
  void aCallOfExactlyAFrameReachesTheHostAndOneCharMoreIsRefusedInTheSandbox() throws IOException {
    var noText = new RpcMessage.Request("sub-1", "ping", Map.of("text", ""));
    var overhead = RPC_PREFIX.length() + ProcessTransport.serializeMessage(noText).length();
    var fits = "x".repeat(MAX_FRAME_CHARS - overhead);

    try (var env = BootstrapEnvironment.reading()) {
      var called = env.inSandbox(() -> HostBridge.__call("ping", Map.of("text", fits)));
      assertEquals(Map.of("text", fits), answer(env, "pong").params());
      assertEquals("pong", Await.value("the call's result", called));

      var refused = env.inSandbox(() -> HostBridge.__call("ping", Map.of("text", fits + "x")));

      var failure = Await.failure("the refused call", refused);
      assertEquals(
          "Frame of 8000001 chars exceeds the limit of 8000000 chars",
          failure.getCause().getMessage());
    }
  }

  /** Takes the request the bridge call sent to the host and answers it with {@code result}. */
  private static RpcMessage.Request answer(BootstrapEnvironment env, Object result) {
    var request = env.nextRequest();
    env.feed(new RpcMessage.Response(request.id(), result));
    return request;
  }
}
