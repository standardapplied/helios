/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.tool.ParameterType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

  @ParameterizedTest
  @ValueSource(strings = {"int", "class", "_", "true", "null", "goto", "const", "__call", "a.b"})
  void aNameTheWrapperCannotDeclareThrowsNamingIt(String name) {
    var thrown =
        assertThrows(
            IllegalArgumentException.class, () -> new HostFunction(name, "desc", params -> "ok"));

    assertEquals(
        "Host function name must be a Java identifier, not a keyword, and not start with __: "
            + name,
        thrown.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"pr\u200Bint", "i\u200Bnt", "yie\u200Bld", "_\u200B_call", "a\u0000b"})
  void aNameWithAnIdentifierIgnorableCharacterThrowsNamingIt(String name) {
    var thrown =
        assertThrows(
            IllegalArgumentException.class, () -> new HostFunction(name, "desc", params -> "ok"));

    assertEquals(
        "Host function name must not contain identifier-ignorable characters: " + name,
        thrown.getMessage());
  }

  @Test
  void yieldThrowsBecauseTheModelCannotCallItUnqualified() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new HostFunction("yield", "desc", params -> "ok"));

    assertTrue(thrown.getMessage().contains("yield"), thrown.getMessage());
  }

  @Test
  void restrictedIdentifiersOtherThanYieldAreAccepted() {
    assertEquals("var", new HostFunction("var", "desc", params -> "ok").name());
    assertEquals("record", new HostFunction("record", "desc", params -> "ok").name());
  }

  @Test
  void twoParametersWithTheSameNameThrowNamingIt() {
    var ticker = HostParameter.required("ticker", ParameterType.STRING, "Ticker symbol");
    var limit = HostParameter.optional("limit", ParameterType.INTEGER, "Row limit");

    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new HostFunction("quote", "desc", List.of(ticker, limit, ticker), p -> "ok"));

    assertEquals("Host function quote declares parameter ticker twice", thrown.getMessage());
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
