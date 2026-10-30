/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.schema.StructuredContentParser.JsonAdapter;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonAdapterTest {

  record Point(int x) {}

  @Test
  void toMapReadsThroughTheReader() throws Exception {
    var map = Map.<String, Object>of("x", 1);

    assertSame(map, new JsonAdapter(json -> map, (m, type) -> null).toMap("{\"x\":1}"));
  }

  @Test
  void toMapPassesTheReadersFailureThrough() {
    var malformed = new IllegalStateException("malformed");
    var adapter =
        new JsonAdapter(
            json -> {
              throw malformed;
            },
            (m, type) -> null);

    assertSame(malformed, assertThrows(IllegalStateException.class, () -> adapter.toMap("{")));
  }

  @Test
  void fromMapConvertsThroughTheConverterToTheRequestedType() throws Exception {
    var adapter = new JsonAdapter(json -> Map.of(), (m, type) -> new Point((Integer) m.get("x")));

    assertEquals(new Point(3), adapter.fromMap(Map.of("x", 3), Point.class));
  }

  @Test
  void fromMapRejectsAConversionToAnotherType() {
    var adapter = new JsonAdapter(json -> Map.of(), (m, type) -> "not a point");

    assertThrows(ClassCastException.class, () -> adapter.fromMap(Map.of(), Point.class));
  }

  @Test
  void rejectsANullReaderByName() {
    var rejected =
        assertThrows(NullPointerException.class, () -> new JsonAdapter(null, (m, type) -> null));

    assertEquals("reader must not be null", rejected.getMessage());
  }

  @Test
  void rejectsANullConverterByName() {
    var rejected =
        assertThrows(NullPointerException.class, () -> new JsonAdapter(json -> Map.of(), null));

    assertEquals("converter must not be null", rejected.getMessage());
  }

  @Test
  void converterFailurePassesThroughFromMap() {
    var incompatible = new IOException("incompatible");
    var adapter =
        new JsonAdapter(
            json -> Map.of(),
            (m, type) -> {
              throw incompatible;
            });

    assertSame(
        incompatible,
        assertThrows(IOException.class, () -> adapter.fromMap(Map.of(), Point.class)));
  }
}
