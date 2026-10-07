/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.test.DeclarationOrderFixture;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutputSchemaTest {

  record Person(String name, int age) {}

  @Test
  void ofCreatesSchemaFromRecord() {
    var outputSchema = OutputSchema.of(Person.class);

    assertEquals(Person.class, outputSchema.type());
    assertNotNull(outputSchema.schema());
    assertEquals("object", outputSchema.schema().type());
  }

  @Test
  void schemaHasCorrectProperties() {
    var outputSchema = OutputSchema.of(Person.class);
    var schema = outputSchema.schema();

    assertEquals(2, schema.properties().size());
    assertEquals("string", schema.properties().get("name").type());
    assertEquals("integer", schema.properties().get("age").type());
  }

  @Test
  void throwsForLeafType() {
    assertThrows(IllegalArgumentException.class, () -> OutputSchema.of(String.class));
  }

  @Test
  void provenanceSchemasListTheirFieldsInDocumentedOrder() {
    var schema = OutputSchema.provenancedOf(DeclarationOrderFixture.Dossier.class).schema();
    var entry = schema.properties().get("provenance").items();
    var source = entry.properties().get("sources").items();

    assertEquals(
        List.of("output", "provenance"), DeclarationOrderFixture.keys(schema.properties()));
    assertEquals(
        DeclarationOrderFixture.DOSSIER_FIELDS,
        DeclarationOrderFixture.keys(schema.properties().get("output").properties()));
    assertEquals(
        List.of("field", "sources", "reasoning", "confidence"),
        DeclarationOrderFixture.keys(entry.properties()));
    assertEquals(List.of("field", "sources", "reasoning", "confidence"), entry.required());
    assertEquals(
        List.of("title", "url", "excerpts"), DeclarationOrderFixture.keys(source.properties()));
    assertEquals(List.of("url", "excerpts"), source.required());
  }
}
