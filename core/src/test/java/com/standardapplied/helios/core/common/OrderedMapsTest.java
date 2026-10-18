/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.test.DeclarationOrderFixture;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderedMapsTest {

  @Test
  void copyKeepsTheIterationOrderOfEightKeys() {
    var source = new LinkedHashMap<String, Integer>();
    for (var name : DeclarationOrderFixture.ARGUMENTS) {
      source.put(name, name.length());
    }

    var copy = OrderedMaps.copyOf(source);
    source.put("later", 5);

    assertEquals(DeclarationOrderFixture.ARGUMENTS, DeclarationOrderFixture.keys(copy));
    assertEquals(6, copy.get("osprey"));
  }

  @Test
  void copyIsUnmodifiable() {
    var copy = OrderedMaps.copyOf(Map.of("k", "v"));

    assertThrows(UnsupportedOperationException.class, () -> copy.put("x", "y"));
    assertThrows(UnsupportedOperationException.class, () -> copy.remove("k"));
  }

  @Test
  void copyRejectsANullMap() {
    assertEquals(
        "map must not be null",
        assertThrows(NullPointerException.class, () -> OrderedMaps.copyOf(null)).getMessage());
  }

  @Test
  void copyRejectsANullKey() {
    var source = new LinkedHashMap<String, String>();
    source.put(null, "v");

    assertEquals(
        "key must not be null",
        assertThrows(NullPointerException.class, () -> OrderedMaps.copyOf(source)).getMessage());
  }

  @Test
  void copyRejectsANullValueNamingItsKey() {
    var source = new LinkedHashMap<String, String>();
    source.put("city", null);

    assertEquals(
        "value of key city must not be null",
        assertThrows(NullPointerException.class, () -> OrderedMaps.copyOf(source)).getMessage());
  }
}
