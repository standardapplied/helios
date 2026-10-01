/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
