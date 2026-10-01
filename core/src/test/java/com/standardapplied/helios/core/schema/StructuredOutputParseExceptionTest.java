/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class StructuredOutputParseExceptionTest {

  @Test
  void exposesErrorsAndRawContent() {
    var errors =
        List.of("field 'a' is required but missing", "field 'b' expected string, got null");
    var ex = new StructuredOutputParseException(errors, "{\"junk\":true}");
    assertEquals(errors, ex.errors());
    assertEquals("{\"junk\":true}", ex.rawContent());
  }

  @Test
  void allowsNullRawContent() {
    var ex = new StructuredOutputParseException(List.of("missing"), null);
    assertNull(ex.rawContent());
  }

  @Test
  void messageIncludesEveryError() {
    var ex =
        new StructuredOutputParseException(
            List.of("first error", "second error", "third error"), "{}");
    assertTrue(ex.getMessage().contains("first error"));
    assertTrue(ex.getMessage().contains("second error"));
    assertTrue(ex.getMessage().contains("third error"));
  }

  @Test
  void correctionMessageIsModelFacingAndExcludesRawContent() {
    var ex =
        new StructuredOutputParseException(
            List.of("field 'foo' is required but missing"),
            "{\"foo\":\"this should not be echoed back to the model on retry\"}");
    var correction = ex.correctionMessage();
    assertTrue(correction.contains("did not match the schema"));
    assertTrue(correction.contains("field 'foo'"));
    assertTrue(correction.contains("re-emit"));
    assertTrue(
        !correction.contains("this should not be echoed"),
        "correctionMessage must not echo raw content back to the model");
  }

  @Test
  void rejectsNullErrors() {
    assertThrows(NullPointerException.class, () -> new StructuredOutputParseException(null, "{}"));
  }

  @Test
  void rejectsEmptyErrors() {
    assertThrows(
        IllegalArgumentException.class, () -> new StructuredOutputParseException(List.of(), "{}"));
  }

  @Test
  void errorsListIsImmutableCopy() {
    var mutable = new java.util.ArrayList<String>();
    mutable.add("first");
    var ex = new StructuredOutputParseException(mutable, null);
    mutable.add("second");
    assertEquals(1, ex.errors().size());
    assertThrows(UnsupportedOperationException.class, () -> ex.errors().add("third"));
  }

  @Test
  void isRuntimeExceptionForCauseChainCompatibility() {
    var ex = new StructuredOutputParseException(List.of("x"), null);
    assertNotNull(ex);
    RuntimeException upcast = ex;
    assertTrue(upcast instanceof StructuredOutputParseException);
  }
}
