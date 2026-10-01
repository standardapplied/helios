/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RpcMessageTest {

  @Test
  void versionConstant() {
    assertEquals("2.0", RpcMessage.VERSION);
  }

  // --- Request ---

  @Test
  void requestSetsFields() {
    var req = new RpcMessage.Request("1", "execute", Map.of("code", "x"));
    assertEquals("1", req.id());
    assertEquals("execute", req.method());
    assertEquals(Map.of("code", "x"), req.params());
  }

  @Test
  void requestNullParams() {
    var req = new RpcMessage.Request("1", "execute", null);
    assertNull(req.params());
  }

  @Test
  void requestNullIdThrows() {
    assertThrows(IllegalArgumentException.class, () -> new RpcMessage.Request(null, "m", null));
  }

  @Test
  void requestBlankIdThrows() {
    assertThrows(IllegalArgumentException.class, () -> new RpcMessage.Request("  ", "m", null));
  }

  @Test
  void requestNullMethodThrows() {
    assertThrows(IllegalArgumentException.class, () -> new RpcMessage.Request("1", null, null));
  }

  @Test
  void requestBlankMethodThrows() {
    assertThrows(IllegalArgumentException.class, () -> new RpcMessage.Request("1", " ", null));
  }

  @Test
  void requestIsRpcMessage() {
    var req = new RpcMessage.Request("1", "test", null);
    assertInstanceOf(RpcMessage.class, req);
  }

  // --- Response ---

  @Test
  void responseSetsFields() {
    var resp = new RpcMessage.Response("1", "result-data");
    assertEquals("1", resp.id());
    assertEquals("result-data", resp.result());
  }

  @Test
  void responseNullResult() {
    var resp = new RpcMessage.Response("1", null);
    assertNull(resp.result());
  }

  @Test
  void responseNullIdThrows() {
    assertThrows(IllegalArgumentException.class, () -> new RpcMessage.Response(null, "ok"));
  }

  @Test
  void responseBlankIdThrows() {
    assertThrows(IllegalArgumentException.class, () -> new RpcMessage.Response("  ", "ok"));
  }

  // --- ErrorResponse ---

  @Test
  void errorResponseSetsFields() {
    var error = RpcError.of(-32600, "bad");
    var errResp = new RpcMessage.ErrorResponse("1", error);
    assertEquals("1", errResp.id());
    assertEquals(error, errResp.error());
  }

  @Test
  void errorResponseNullId() {
    var error = RpcError.of(-32700, "parse");
    var errResp = new RpcMessage.ErrorResponse(null, error);
    assertNull(errResp.id());
  }

  @Test
  void errorResponseNullErrorThrows() {
    assertThrows(IllegalArgumentException.class, () -> new RpcMessage.ErrorResponse("1", null));
  }

  // --- Notification ---

  @Test
  void notificationSetsFields() {
    var notif = new RpcMessage.Notification("progress", Map.of("pct", 50));
    assertEquals("progress", notif.method());
    assertEquals(Map.of("pct", 50), notif.params());
  }

  @Test
  void notificationNullParams() {
    var notif = new RpcMessage.Notification("ping", null);
    assertNull(notif.params());
  }

  @Test
  void notificationNullMethodThrows() {
    assertThrows(IllegalArgumentException.class, () -> new RpcMessage.Notification(null, null));
  }

  @Test
  void notificationBlankMethodThrows() {
    assertThrows(IllegalArgumentException.class, () -> new RpcMessage.Notification("  ", null));
  }

  // --- Sealed exhaustiveness ---

  @Test
  void patternMatchCoversAllVariants() {
    RpcMessage msg = new RpcMessage.Request("1", "test", null);
    var result =
        switch (msg) {
          case RpcMessage.Request r -> "request";
          case RpcMessage.Response r -> "response";
          case RpcMessage.ErrorResponse e -> "error";
          case RpcMessage.Notification n -> "notification";
        };
    assertEquals("request", result);
  }
}
