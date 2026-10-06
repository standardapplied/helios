/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonBindingTest {

  record Point(int x) {}

  @Test
  void toMapReadsThroughTheReader() throws Exception {
    var map = Map.<String, Object>of("x", 1);

    assertSame(map, new JsonBinding(json -> map, (m, type) -> null).toMap("{\"x\":1}"));
  }

  @Test
  void toMapPassesTheReadersFailureThrough() {
    var malformed = new IllegalStateException("malformed");
    var binding =
        new JsonBinding(
            json -> {
              throw malformed;
            },
            (m, type) -> null);

    assertSame(malformed, assertThrows(IllegalStateException.class, () -> binding.toMap("{")));
  }

  @Test
  void fromMapConvertsThroughTheConverterToTheRequestedType() {
    var binding = new JsonBinding(json -> Map.of(), (m, type) -> new Point((Integer) m.get("x")));

    assertEquals(new Point(3), binding.fromMap(Map.of("x", 3), Point.class));
  }

  @Test
  void fromMapRejectsAConversionToAnotherType() {
    var binding = new JsonBinding(json -> Map.of(), (m, type) -> "not a point");

    assertThrows(ClassCastException.class, () -> binding.fromMap(Map.of(), Point.class));
  }
}
