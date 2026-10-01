/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HostFunctionTest {

  @Test
  void constructorSetsFields() {
    HostFunctionHandler handler = params -> "ok";
    var fn = new HostFunction("test", "A test function", handler);
    assertEquals("test", fn.name());
    assertEquals("A test function", fn.description());
    assertEquals(handler, fn.handler());
  }

  @Test
  void nullNameThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new HostFunction(null, "desc", params -> "ok"));
  }

  @Test
  void blankNameThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new HostFunction("  ", "desc", params -> "ok"));
  }

  @Test
  void nullDescriptionThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new HostFunction("name", null, params -> "ok"));
  }

  @Test
  void blankDescriptionThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new HostFunction("name", " ", params -> "ok"));
  }

  @Test
  void nullHandlerThrows() {
    assertThrows(IllegalArgumentException.class, () -> new HostFunction("name", "desc", null));
  }

  @Test
  void handlerCanBeInvoked() throws Exception {
    var fn = new HostFunction("echo", "Echoes input", params -> params.get("msg"));
    var result = fn.handler().handle(Map.of("msg", "hello"));
    assertEquals("hello", result);
  }
}
