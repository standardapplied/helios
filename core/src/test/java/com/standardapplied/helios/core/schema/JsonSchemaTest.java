/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.test.DeclarationOrderFixture;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonSchemaTest {

  @Test
  void stringSchema() {
    var schema = JsonSchema.string();

    assertEquals("string", schema.type());
    assertNull(schema.properties());
    assertNull(schema.items());
  }

  @Test
  void stringSchemaWithDescription() {
    var schema = JsonSchema.string("A person's name");

    assertEquals("string", schema.type());
    assertEquals("A person's name", schema.description());
  }

  @Test
  void integerSchema() {
    var schema = JsonSchema.integer();

    assertEquals("integer", schema.type());
  }

  @Test
  void numberSchema() {
    var schema = JsonSchema.number();

    assertEquals("number", schema.type());
  }

  @Test
  void booleanSchema() {
    var schema = JsonSchema.bool();

    assertEquals("boolean", schema.type());
  }

  @Test
  void arraySchema() {
    var schema = JsonSchema.array(JsonSchema.string());

    assertEquals("array", schema.type());
    assertEquals("string", schema.items().type());
  }

  @Test
  void enumSchema() {
    var schema = JsonSchema.enumOf(List.of("RED", "GREEN", "BLUE"));

    assertEquals("string", schema.type());
    assertEquals(List.of("RED", "GREEN", "BLUE"), schema.enumValues());
  }

  @Test
  void objectSchema() {
    var schema =
        JsonSchema.object()
            .withProperty("name", JsonSchema.string(), true)
            .withProperty("age", JsonSchema.integer())
            .build();

    assertEquals("object", schema.type());
    assertEquals(2, schema.properties().size());
    assertEquals("string", schema.properties().get("name").type());
    assertEquals("integer", schema.properties().get("age").type());
    assertEquals(List.of("name"), schema.required());
  }

  @Test
  void objectSchemaWithDescription() {
    var schema =
        JsonSchema.object()
            .withDescription("A person object")
            .withProperty("id", JsonSchema.string())
            .build();

    assertEquals("A person object", schema.description());
  }

  @Test
  void objectSchemaWithRequired() {
    var schema =
        JsonSchema.object()
            .withProperty("a", JsonSchema.string())
            .withProperty("b", JsonSchema.string())
            .withRequired("a", "b")
            .build();

    assertEquals(List.of("a", "b"), schema.required());
  }

  @Test
  void duplicateRequiredEntriesAreDeduplicated() {
    var schema =
        JsonSchema.object()
            .withProperty("name", JsonSchema.string(), true)
            .withRequired("name")
            .build();

    assertEquals(List.of("name"), schema.required());
  }

  @Test
  void toMapSimpleTypes() {
    assertEquals(Map.of("type", "string"), JsonSchema.string().toMap());
    assertEquals(Map.of("type", "integer"), JsonSchema.integer().toMap());
    assertEquals(Map.of("type", "number"), JsonSchema.number().toMap());
    assertEquals(Map.of("type", "boolean"), JsonSchema.bool().toMap());
  }

  @Test
  void toMapWithDescription() {
    var map = JsonSchema.string("test description").toMap();

    assertEquals("string", map.get("type"));
    assertEquals("test description", map.get("description"));
  }

  @Test
  void toMapArray() {
    var map = JsonSchema.array(JsonSchema.integer()).toMap();

    assertEquals("array", map.get("type"));
    @SuppressWarnings("unchecked")
    var items = (Map<String, Object>) map.get("items");
    assertEquals("integer", items.get("type"));
  }

  @Test
  void toMapEnum() {
    var map = JsonSchema.enumOf(List.of("A", "B")).toMap();

    assertEquals("string", map.get("type"));
    assertEquals(List.of("A", "B"), map.get("enum"));
  }

  @Test
  void toMapWithFormat() {
    var schema = new JsonSchema("string", null, null, null, null, null, "date-time", null);
    var map = schema.toMap();

    assertEquals("string", map.get("type"));
    assertEquals("date-time", map.get("format"));
  }

  @Test
  void toMapWithEmptyProperties() {
    var schema =
        new JsonSchema("object", new LinkedHashMap<>(), null, null, null, null, null, null);
    var map = schema.toMap();

    assertEquals("object", map.get("type"));
    assertNull(map.get("properties"));
  }

  @Test
  void mapSchema() {
    var schema = JsonSchema.map(JsonSchema.string());

    assertEquals("object", schema.type());
    assertNull(schema.properties());
    assertEquals("string", schema.additionalProperties().type());
  }

  @Test
  void mapSchemaWithComplexValueType() {
    var schema = JsonSchema.map(JsonSchema.array(JsonSchema.string()));

    assertEquals("object", schema.type());
    assertEquals("array", schema.additionalProperties().type());
    assertEquals("string", schema.additionalProperties().items().type());
  }

  @Test
  void toMapWithAdditionalProperties() {
    var schema = JsonSchema.map(JsonSchema.array(JsonSchema.string()));
    var map = schema.toMap();

    assertEquals("object", map.get("type"));
    @SuppressWarnings("unchecked")
    var additionalProps = (Map<String, Object>) map.get("additionalProperties");
    assertEquals("array", additionalProps.get("type"));
    @SuppressWarnings("unchecked")
    var items = (Map<String, Object>) additionalProps.get("items");
    assertEquals("string", items.get("type"));
  }

  @Test
  void toMapOmitsEmptyRequiredAndEnum() {
    var schema = new JsonSchema("string", null, null, List.of(), List.of(), null, null, null);

    assertEquals(Map.of("type", "string"), schema.toMap());
  }

  @Test
  void toMapWithoutAdditionalProperties() {
    var map = JsonSchema.string().toMap();
    assertNull(map.get("additionalProperties"));
  }

  @Test
  void builderWithPropertyNotRequired() {
    var schema = JsonSchema.object().withProperty("optional", JsonSchema.string(), false).build();

    assertNull(schema.required());
  }

  @Test
  void toMapObject() {
    var schema =
        JsonSchema.object()
            .withProperty("name", JsonSchema.string(), true)
            .withProperty("age", JsonSchema.integer())
            .build();
    var map = schema.toMap();

    assertEquals("object", map.get("type"));
    assertEquals(List.of("name"), map.get("required"));

    @SuppressWarnings("unchecked")
    var properties = (Map<String, Object>) map.get("properties");
    assertEquals(2, properties.size());
  }

  @Test
  void schemaPropertiesAreAnUnmodifiableCopyInTheGivenOrder() {
    var properties = new LinkedHashMap<String, JsonSchema>();
    properties.put("zeta", JsonSchema.string());
    properties.put("alpha", JsonSchema.integer());
    var schema = new JsonSchema("object", properties, null, null, null, null, null, null);
    properties.put("later", JsonSchema.bool());

    assertEquals(List.of("zeta", "alpha"), DeclarationOrderFixture.keys(schema.properties()));
    assertThrows(
        UnsupportedOperationException.class,
        () -> schema.properties().put("x", JsonSchema.string()));
    assertNull(JsonSchema.string().properties());
  }

  @Test
  void schemaRejectsANullPropertyNameOrSchema() {
    var nullName = JsonSchema.object().withProperty(null, JsonSchema.string());
    var nullSchema = JsonSchema.object().withProperty("city", null);

    assertEquals(
        "key must not be null",
        assertThrows(NullPointerException.class, nullName::build).getMessage());
    assertEquals(
        "value of key city must not be null",
        assertThrows(NullPointerException.class, nullSchema::build).getMessage());
  }

  @Test
  void builderKeepsPropertyAndRequiredOrder() {
    var builder = JsonSchema.object();
    for (var name : DeclarationOrderFixture.DOSSIER_FIELDS) {
      builder.withProperty(name, JsonSchema.string(), true);
    }
    var schema = builder.build();
    @SuppressWarnings("unchecked")
    var sent = (Map<String, Object>) schema.toMap().get("properties");

    assertEquals(
        DeclarationOrderFixture.DOSSIER_FIELDS, DeclarationOrderFixture.keys(schema.properties()));
    assertEquals(DeclarationOrderFixture.DOSSIER_FIELDS, schema.required());
    assertEquals(DeclarationOrderFixture.DOSSIER_FIELDS, DeclarationOrderFixture.keys(sent));
  }
}
