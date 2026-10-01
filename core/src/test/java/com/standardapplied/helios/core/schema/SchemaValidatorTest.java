/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaValidatorTest {

  @Test
  void nullSchemaPasses() {
    assertTrue(SchemaValidator.validate("anything", null).isEmpty());
  }

  @Test
  void unknownTypePasses() {
    var schema = new JsonSchema("custom-type", null, null, null, null, null, null, null);
    assertTrue(SchemaValidator.validate("anything", schema).isEmpty());
  }

  @Test
  void schemaWithNullTypePasses() {
    var schema = new JsonSchema(null, null, null, null, null, null, null, null);
    assertTrue(SchemaValidator.validate("anything", schema).isEmpty());
  }

  @Test
  void stringAcceptsString() {
    assertTrue(SchemaValidator.validate("hello", JsonSchema.string()).isEmpty());
  }

  @Test
  void stringRejectsNonString() {
    var errors = SchemaValidator.validate(42, JsonSchema.string());
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("expected string"));
  }

  @Test
  void integerAcceptsIntLongShortByte() {
    assertTrue(SchemaValidator.validate(1, JsonSchema.integer()).isEmpty());
    assertTrue(SchemaValidator.validate(1L, JsonSchema.integer()).isEmpty());
    assertTrue(SchemaValidator.validate((short) 1, JsonSchema.integer()).isEmpty());
    assertTrue(SchemaValidator.validate((byte) 1, JsonSchema.integer()).isEmpty());
  }

  @Test
  void integerAcceptsWholeDouble() {
    assertTrue(SchemaValidator.validate(2.0, JsonSchema.integer()).isEmpty());
  }

  @Test
  void integerRejectsFractionalDouble() {
    var errors = SchemaValidator.validate(2.5, JsonSchema.integer());
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("non-integer"));
  }

  @Test
  void integerRejectsString() {
    var errors = SchemaValidator.validate("3", JsonSchema.integer());
    assertEquals(1, errors.size());
  }

  @Test
  void numberAcceptsAnyNumeric() {
    assertTrue(SchemaValidator.validate(2.5, JsonSchema.number()).isEmpty());
    assertTrue(SchemaValidator.validate(7, JsonSchema.number()).isEmpty());
  }

  @Test
  void numberRejectsString() {
    var errors = SchemaValidator.validate("nope", JsonSchema.number());
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("expected number"));
  }

  @Test
  void booleanAcceptsBoolean() {
    assertTrue(SchemaValidator.validate(true, JsonSchema.bool()).isEmpty());
    assertTrue(SchemaValidator.validate(false, JsonSchema.bool()).isEmpty());
  }

  @Test
  void booleanRejectsNonBoolean() {
    var errors = SchemaValidator.validate("true", JsonSchema.bool());
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("expected boolean"));
  }

  @Test
  void enumAcceptsAllowedValue() {
    var schema = JsonSchema.enumOf(List.of("yes", "no"));
    assertTrue(SchemaValidator.validate("yes", schema).isEmpty());
  }

  @Test
  void enumRejectsDisallowedValue() {
    var schema = JsonSchema.enumOf(List.of("yes", "no"));
    var errors = SchemaValidator.validate("maybe", schema);
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("must be one of"));
  }

  @Test
  void arrayAcceptsList() {
    var schema = JsonSchema.array(JsonSchema.string());
    assertTrue(SchemaValidator.validate(List.of("a", "b"), schema).isEmpty());
  }

  @Test
  void arrayRejectsNonList() {
    var schema = JsonSchema.array(JsonSchema.string());
    var errors = SchemaValidator.validate("a,b", schema);
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("expected array"));
  }

  @Test
  void arrayValidatesEachItem() {
    var schema = JsonSchema.array(JsonSchema.integer());
    var errors = SchemaValidator.validate(List.of(1, "two", 3, "four"), schema);
    assertEquals(2, errors.size());
    assertTrue(errors.get(0).contains("[1]"));
    assertTrue(errors.get(1).contains("[3]"));
  }

  @Test
  void objectRejectsNonMap() {
    var schema = JsonSchema.object().withProperty("x", JsonSchema.string(), true).build();
    var errors = SchemaValidator.validate("not-a-map", schema);
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("expected object"));
  }

  @Test
  void objectFlagsMissingRequiredField() {
    var schema =
        JsonSchema.object()
            .withProperty("name", JsonSchema.string(), true)
            .withProperty("count", JsonSchema.integer(), true)
            .build();
    var errors = SchemaValidator.validate(Map.of("name", "x"), schema);
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("count"));
    assertTrue(errors.get(0).contains("required"));
  }

  @Test
  void objectFlagsExplicitNullForRequiredField() {
    var schema = JsonSchema.object().withProperty("name", JsonSchema.string(), true).build();
    var data = new java.util.HashMap<String, Object>();
    data.put("name", null);
    var errors = SchemaValidator.validate(data, schema);
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("required"));
  }

  @Test
  void objectValidatesNestedProperties() {
    var inner = JsonSchema.object().withProperty("city", JsonSchema.string(), true).build();
    var outer = JsonSchema.object().withProperty("address", inner, true).build();
    var errors = SchemaValidator.validate(Map.of("address", Map.of("city", 42)), outer);
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("address.city"));
    assertTrue(errors.get(0).contains("expected string"));
  }

  @Test
  void additionalPropertiesValidated() {
    var schema = new JsonSchema("object", null, null, null, null, null, null, JsonSchema.integer());
    var errors = SchemaValidator.validate(Map.of("a", 1, "b", "two"), schema);
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("'b'"));
  }

  @Test
  void additionalPropertiesSkipsDeclaredOnes() {
    var schema =
        new JsonSchema(
            "object",
            Map.of("declared", JsonSchema.string()),
            null,
            null,
            null,
            null,
            null,
            JsonSchema.integer());
    var errors = SchemaValidator.validate(Map.of("declared", "ok", "extra", 7), schema);
    assertTrue(errors.isEmpty());
  }

  @Test
  void describeTypeCoversCommonValues() {
    var schema = JsonSchema.string();
    assertTrue(SchemaValidator.validate(null, schema).get(0).contains("null"));
    assertTrue(SchemaValidator.validate(Map.of(), schema).get(0).contains("object"));
    assertTrue(SchemaValidator.validate(List.of(), schema).get(0).contains("array"));
    assertTrue(SchemaValidator.validate(true, schema).get(0).contains("boolean"));
    assertTrue(SchemaValidator.validate(42, schema).get(0).contains("number"));
  }
}
