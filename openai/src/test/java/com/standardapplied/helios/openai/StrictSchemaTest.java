/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.DeclarationOrderFixture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StrictSchemaTest {

  @Test
  @SuppressWarnings("unchecked")
  void addAdditionalPropertiesFalseSimpleObject() {
    var schema =
        Map.<String, Object>of(
            "type", "object",
            "properties", Map.of("name", Map.of("type", "string")),
            "required", List.of("name"));

    var result = StrictSchema.addAdditionalPropertiesFalse(schema);

    assertEquals(false, result.get("additionalProperties"));
    var props = (Map<String, Object>) result.get("properties");
    var nameSchema = (Map<String, Object>) props.get("name");
    assertEquals("string", nameSchema.get("type"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void addAdditionalPropertiesFalseNestedObject() {
    var schema =
        Map.<String, Object>of(
            "type",
            "object",
            "properties",
            Map.of(
                "address",
                Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string")))));

    var result = StrictSchema.addAdditionalPropertiesFalse(schema);

    assertEquals(false, result.get("additionalProperties"));
    var props = (Map<String, Object>) result.get("properties");
    var addressSchema = (Map<String, Object>) props.get("address");
    assertEquals(false, addressSchema.get("additionalProperties"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void addAdditionalPropertiesFalseArray() {
    var schema =
        Map.<String, Object>of(
            "type",
            "array",
            "items",
            Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))));

    var result = StrictSchema.addAdditionalPropertiesFalse(schema);

    var items = (Map<String, Object>) result.get("items");
    assertEquals(false, items.get("additionalProperties"));
  }

  @Test
  void addAdditionalPropertiesFalseLeafType() {
    var schema = Map.<String, Object>of("type", "string");

    var result = StrictSchema.addAdditionalPropertiesFalse(schema);

    assertEquals("string", result.get("type"));
    assertNull(result.get("additionalProperties"));
  }

  @Test
  void hasOpenMapShapeReturnsFalseForFlatRecord() {
    var schema =
        Map.<String, Object>of(
            "type",
            "object",
            "properties",
            Map.of("name", Map.of("type", "string"), "count", Map.of("type", "integer")),
            "required",
            List.of("name", "count"));
    assertFalse(StrictSchema.hasOpenMapShape(schema));
  }

  @Test
  void hasOpenMapShapeDetectsTopLevelOpenMap() {
    var schema =
        Map.<String, Object>of("type", "object", "additionalProperties", Map.of("type", "string"));
    assertTrue(StrictSchema.hasOpenMapShape(schema));
  }

  @Test
  void hasOpenMapShapeDetectsOpenMapNestedInProperty() {
    // record Out(Map<String, List<String>> targetToSources)
    var schema =
        Map.<String, Object>of(
            "type",
            "object",
            "properties",
            Map.of(
                "targetToSources",
                Map.of(
                    "type",
                    "object",
                    "additionalProperties",
                    Map.of("type", "array", "items", Map.of("type", "string")))),
            "required",
            List.of("targetToSources"));
    assertTrue(
        StrictSchema.hasOpenMapShape(schema),
        "Map<String, List<String>> inside a record property must be detected so strict mode is"
            + " disabled — strict mode rejects open Maps with HTTP 400");
  }

  @Test
  void hasOpenMapShapeDetectsOpenMapNestedInArrayItems() {
    // List<Map<String, String>>
    var schema =
        Map.<String, Object>of(
            "type",
            "array",
            "items",
            Map.of("type", "object", "additionalProperties", Map.of("type", "string")));
    assertTrue(StrictSchema.hasOpenMapShape(schema));
  }

  @Test
  void hasOpenMapShapeIgnoresAdditionalPropertiesFalse() {
    // After addAdditionalPropertiesFalse has been applied, additionalProperties=false should NOT
    // count as an open map.
    var schema =
        Map.<String, Object>of(
            "type",
            "object",
            "properties",
            Map.of("name", Map.of("type", "string")),
            "additionalProperties",
            false);
    assertFalse(StrictSchema.hasOpenMapShape(schema));
  }

  @Test
  void hasOpenMapShapeReturnsFalseForNull() {
    assertFalse(StrictSchema.hasOpenMapShape(null));
  }

  @Test
  @SuppressWarnings("unchecked")
  void addAdditionalPropertiesFalsePreservesMapValueSchema() {
    // Map<String, List<String>> produces: {type: object, additionalProperties: {type: array, ...}}
    var schema =
        Map.<String, Object>of(
            "type",
            "object",
            "additionalProperties",
            Map.of("type", "array", "items", Map.of("type", "string")));

    var result = StrictSchema.addAdditionalPropertiesFalse(schema);

    // additionalProperties should be recursed into, NOT overwritten with false
    var addlProps = (Map<String, Object>) result.get("additionalProperties");
    assertNotNull(addlProps, "Map value schema should be preserved");
    assertEquals("array", addlProps.get("type"));

    var items = (Map<String, Object>) addlProps.get("items");
    assertEquals("string", items.get("type"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void addAdditionalPropertiesFalseRecursesNestedMapValueSchema() {
    // Map<String, Record> — additionalProperties is an object that needs its own false
    var schema =
        Map.<String, Object>of(
            "type",
            "object",
            "additionalProperties",
            Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))));

    var result = StrictSchema.addAdditionalPropertiesFalse(schema);

    var addlProps = (Map<String, Object>) result.get("additionalProperties");
    assertNotNull(addlProps);
    assertEquals("object", addlProps.get("type"));
    assertEquals(false, addlProps.get("additionalProperties"));
  }

  @Test
  void strictSchemaKeepsEveryMapInDeclaredOrder() {
    var schema = DeclarationOrderFixture.schema().schema().toMap();

    var strict = StrictSchema.addAdditionalPropertiesFalse(schema);

    assertEquals(
        List.of("type", "properties", "required", "additionalProperties"),
        DeclarationOrderFixture.keys(strict));
    assertEquals(
        DeclarationOrderFixture.DOSSIER_FIELDS, DeclarationOrderFixture.keys(properties(strict)));
    assertEquals(
        List.of("northing", "easting"),
        DeclarationOrderFixture.keys(properties(properties(strict).get("scene"))));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> properties(Object schema) {
    return (Map<String, Object>) ((Map<String, Object>) schema).get("properties");
  }
}
