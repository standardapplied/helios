/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RpcErrorTest {

  @Test
  void constructorSetsFields() {
    var error = new RpcError(42, "test error", "extra");
    assertEquals(42, error.code());
    assertEquals("test error", error.message());
    assertEquals("extra", error.data());
  }

  @Test
  void nullMessageThrows() {
    assertThrows(IllegalArgumentException.class, () -> new RpcError(1, null, null));
  }

  @Test
  void blankMessageThrows() {
    assertThrows(IllegalArgumentException.class, () -> new RpcError(1, "  ", null));
  }

  @Test
  void ofCreatesWithoutData() {
    var error = RpcError.of(-32600, "Invalid request");
    assertEquals(-32600, error.code());
    assertEquals("Invalid request", error.message());
    assertNull(error.data());
  }

  @Test
  void methodNotFoundFactory() {
    var error = RpcError.methodNotFound("doStuff");
    assertEquals(RpcError.METHOD_NOT_FOUND, error.code());
    assertEquals("Method not found: doStuff", error.message());
    assertNull(error.data());
  }

  @Test
  void internalErrorFactory() {
    var error = RpcError.internalError("boom");
    assertEquals(RpcError.INTERNAL_ERROR, error.code());
    assertEquals("boom", error.message());
    assertNull(error.data());
  }

  @Test
  void standardErrorCodes() {
    assertEquals(-32700, RpcError.PARSE_ERROR);
    assertEquals(-32600, RpcError.INVALID_REQUEST);
    assertEquals(-32601, RpcError.METHOD_NOT_FOUND);
    assertEquals(-32602, RpcError.INVALID_PARAMS);
    assertEquals(-32603, RpcError.INTERNAL_ERROR);
  }
}
