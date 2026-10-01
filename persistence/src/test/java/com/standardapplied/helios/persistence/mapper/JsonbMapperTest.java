/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonbMapperTest {

  @Test
  void toJsonbWithNullMap() {
    assertEquals("{}", JsonbMapper.toJsonb(null));
  }

  @Test
  void toJsonbWithEmptyMap() {
    assertEquals("{}", JsonbMapper.toJsonb(Map.of()));
  }

  @Test
  void toJsonbWithEntries() {
    var json = JsonbMapper.toJsonb(Map.of("key", "value"));
    assertTrue(json.contains("\"key\""));
    assertTrue(json.contains("\"value\""));
  }

  @Test
  void fromJsonbWithNull() {
    assertEquals(Map.of(), JsonbMapper.fromJsonb(null));
  }

  @Test
  void fromJsonbWithBlank() {
    assertEquals(Map.of(), JsonbMapper.fromJsonb(""));
    assertEquals(Map.of(), JsonbMapper.fromJsonb("  "));
  }

  @Test
  void fromJsonbWithEmptyObject() {
    assertEquals(Map.of(), JsonbMapper.fromJsonb("{}"));
  }

  @Test
  void fromJsonbRoundTrip() {
    var original = Map.of("agent", "test", "model", "gemini");
    var json = JsonbMapper.toJsonb(original);
    var parsed = JsonbMapper.fromJsonb(json);
    assertEquals(original, parsed);
  }

  @Test
  void fromJsonbWithInvalidJsonThrows() {
    assertThrows(IllegalArgumentException.class, () -> JsonbMapper.fromJsonb("not json"));
  }

  // --- listToJsonb / listFromJsonb tests ---

  @Test
  void listToJsonbWithNull() {
    assertEquals("[]", JsonbMapper.listToJsonb(null));
  }

  @Test
  void listToJsonbWithEmptyList() {
    assertEquals("[]", JsonbMapper.listToJsonb(List.of()));
  }

  @Test
  void listToJsonbWithValues() {
    var json = JsonbMapper.listToJsonb(List.of("alpha", "beta"));
    assertTrue(json.contains("\"alpha\""));
    assertTrue(json.contains("\"beta\""));
  }

  @Test
  void listFromJsonbWithNull() {
    assertEquals(List.of(), JsonbMapper.listFromJsonb(null));
  }

  @Test
  void listFromJsonbWithBlank() {
    assertEquals(List.of(), JsonbMapper.listFromJsonb(""));
    assertEquals(List.of(), JsonbMapper.listFromJsonb("  "));
  }

  @Test
  void listFromJsonbWithEmptyArray() {
    assertEquals(List.of(), JsonbMapper.listFromJsonb("[]"));
  }

  @Test
  void listFromJsonbRoundTrip() {
    var original = List.of("production", "v2", "experiment");
    var json = JsonbMapper.listToJsonb(original);
    var parsed = JsonbMapper.listFromJsonb(json);
    assertEquals(original, parsed);
  }

  @Test
  void listFromJsonbWithInvalidJsonThrows() {
    assertThrows(IllegalArgumentException.class, () -> JsonbMapper.listFromJsonb("not json"));
  }

  // --- objectToJsonb cap tests ---

  @Test
  void objectToJsonbNullReturnsNull() {
    assertEquals(null, JsonbMapper.objectToJsonb(null));
  }

  @Test
  void objectToJsonbWithinCap() {
    var json = JsonbMapper.objectToJsonb(Map.of("k", "v"));
    assertTrue(json.contains("\"k\""));
  }

  @Test
  void objectToJsonbExceedingDefaultCapThrows() {
    var huge = "x".repeat(JsonbMapper.MAX_JSONB_BYTES);
    var error =
        assertThrows(
            IllegalArgumentException.class, () -> JsonbMapper.objectToJsonb(Map.of("v", huge)));
    assertTrue(error.getMessage().contains("JSONB payload exceeds"));
    assertTrue(error.getMessage().contains(String.valueOf(JsonbMapper.MAX_JSONB_BYTES)));
  }

  @Test
  void objectToJsonbHigherCapAllowsLargerPayload() {
    var huge = "x".repeat(JsonbMapper.MAX_JSONB_BYTES);
    var json = JsonbMapper.objectToJsonb(Map.of("v", huge), JsonbMapper.MAX_JSONB_BYTES * 2);
    assertTrue(json.contains(huge));
  }

  @Test
  void objectToJsonbRejectsNonPositiveCap() {
    assertThrows(
        IllegalArgumentException.class, () -> JsonbMapper.objectToJsonb(Map.of("k", "v"), 0));
    assertThrows(
        IllegalArgumentException.class, () -> JsonbMapper.objectToJsonb(Map.of("k", "v"), -1));
  }
}
