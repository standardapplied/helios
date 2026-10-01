/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.tool.ParameterType;
import org.junit.jupiter.api.Test;

class HostParameterTest {

  @Test
  void requiredAndOptionalFactories() {
    var req = HostParameter.required("ticker", ParameterType.STRING, "Ticker symbol");
    assertTrue(req.required());
    assertEquals("ticker", req.name());
    assertEquals(ParameterType.STRING, req.type());

    var opt = HostParameter.optional("limit", ParameterType.INTEGER, "Row limit");
    assertFalse(opt.required());
    assertEquals("limit", opt.name());
  }

  @Test
  void rejectsNullOrBlankName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HostParameter(null, ParameterType.STRING, "x", true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HostParameter("", ParameterType.STRING, "x", true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HostParameter("   ", ParameterType.STRING, "x", true));
  }

  @Test
  void rejectsNonIdentifierName() {
    // Starts with digit
    assertThrows(
        IllegalArgumentException.class,
        () -> new HostParameter("1ticker", ParameterType.STRING, "x", true));
    // Contains hyphen
    assertThrows(
        IllegalArgumentException.class,
        () -> new HostParameter("series-id", ParameterType.STRING, "x", true));
    // Contains dot
    assertThrows(
        IllegalArgumentException.class,
        () -> new HostParameter("a.b", ParameterType.STRING, "x", true));
  }

  @Test
  void allowsUnderscorePrefix() {
    // Internal names like __raw should be permissible — same rule as Java identifiers.
    var p = new HostParameter("_raw", ParameterType.STRING, "raw value", true);
    assertEquals("_raw", p.name());
  }

  @Test
  void rejectsNullType() {
    assertThrows(IllegalArgumentException.class, () -> new HostParameter("x", null, "desc", true));
  }

  @Test
  void rejectsNullOrBlankDescription() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HostParameter("x", ParameterType.STRING, null, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HostParameter("x", ParameterType.STRING, "", true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HostParameter("x", ParameterType.STRING, "   ", true));
  }

  @Test
  void javaTypeForStringIsBoxedString() {
    assertEquals(
        "java.lang.String", HostParameter.required("s", ParameterType.STRING, "x").javaType());
  }

  @Test
  void javaTypeForIntegerBoxesToLong() {
    // Long, not Integer — the JSON Schema "integer" type maps to whatever-fits values from the
    // wire; use Long so 32-bit and 64-bit values both fit and Map.put autoboxes from long.
    assertEquals(
        "java.lang.Long", HostParameter.required("n", ParameterType.INTEGER, "x").javaType());
  }

  @Test
  void javaTypeForNumberBoxesToDouble() {
    assertEquals(
        "java.lang.Double", HostParameter.required("n", ParameterType.NUMBER, "x").javaType());
  }

  @Test
  void javaTypeForBooleanBoxesToBoolean() {
    assertEquals(
        "java.lang.Boolean", HostParameter.required("b", ParameterType.BOOLEAN, "x").javaType());
  }

  @Test
  void javaTypeForArrayIsListObject() {
    assertEquals(
        "java.util.List<java.lang.Object>",
        HostParameter.required("xs", ParameterType.ARRAY, "x").javaType());
  }

  @Test
  void javaTypeForObjectIsMapStringObject() {
    assertEquals(
        "java.util.Map<java.lang.String, java.lang.Object>",
        HostParameter.required("m", ParameterType.OBJECT, "x").javaType());
  }
}
