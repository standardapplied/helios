/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Confidence;
import ai.singlr.core.common.Provenanced;
import ai.singlr.core.common.SubmitValidator;
import ai.singlr.core.common.ValidationResult;
import ai.singlr.core.schema.OutputSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for the package-private {@link SubmitValidation} pipeline. The class is shared
 * between {@link SubmitTool} and {@link SubmitFunction}; this is the single source of truth for
 * "what does valid mean", so direct coverage matters even though both call sites exercise it
 * transitively.
 */
final class SubmitValidationTest {

  public record Answer(String value, int count) {}

  private static Map<String, Object> output(String value, int count) {
    var m = new HashMap<String, Object>();
    m.put("value", value);
    m.put("count", count);
    return m;
  }

  @Test
  void nullOutputRejected() {
    var result = SubmitValidation.validate(null, OutputSchema.of(Answer.class));
    var rej = assertInstanceOf(SubmitValidation.Result.Rejected.class, result);
    assertTrue(rej.error().contains("output"));
  }

  @Test
  void schemaMismatchRejected() {
    var bad = new HashMap<String, Object>();
    bad.put("value", "x");
    // missing 'count'
    var result = SubmitValidation.validate(bad, OutputSchema.of(Answer.class));
    var rej = assertInstanceOf(SubmitValidation.Result.Rejected.class, result);
    assertTrue(rej.error().contains("count"));
    assertTrue(rej.error().contains("Submit validation failed"));
  }

  @Test
  void validShapeAccepted() {
    var result = SubmitValidation.validate(output("hi", 3), OutputSchema.of(Answer.class));
    assertInstanceOf(SubmitValidation.Result.Accepted.class, result);
  }

  @Test
  void submitValidatorFailureSurfacesInRejection() {
    SubmitValidator<Answer> validator = value -> ValidationResult.failure("must include 'world'");
    var schema = OutputSchema.of(Answer.class).withSubmitValidator(validator);
    var result = SubmitValidation.validate(output("hi", 3), schema);
    var rej = assertInstanceOf(SubmitValidation.Result.Rejected.class, result);
    assertTrue(rej.error().contains("must include 'world'"));
  }

  @Test
  void submitValidatorPassReturnsParsedTypedValue() {
    SubmitValidator<Answer> validator = value -> ValidationResult.success();
    var schema = OutputSchema.of(Answer.class).withSubmitValidator(validator);
    var result = SubmitValidation.validate(output("hi", 3), schema);
    var acc = assertInstanceOf(SubmitValidation.Result.Accepted.class, result);
    assertInstanceOf(Answer.class, acc.value());
    var a = (Answer) acc.value();
    assertEquals("hi", a.value());
    assertEquals(3, a.count());
  }

  @Test
  void provenancedSchemaAcceptsCompleteEnvelope() {
    var schema = OutputSchema.provenancedOf(Answer.class);
    var envelope = new HashMap<String, Object>();
    envelope.put("output", output("hi", 3));
    envelope.put(
        "provenance",
        List.of(
            provEntry("value", "https://x.example/a", Confidence.MEDIUM),
            provEntry("count", "https://x.example/b", Confidence.HIGH)));
    var result = SubmitValidation.validate(envelope, schema);
    var acc = assertInstanceOf(SubmitValidation.Result.Accepted.class, result);
    assertInstanceOf(Provenanced.class, acc.value());
  }

  @Test
  void provenancedSchemaRejectsMissingProvenanceEntry() {
    var schema = OutputSchema.provenancedOf(Answer.class);
    var envelope = new HashMap<String, Object>();
    envelope.put("output", output("hi", 3));
    envelope.put(
        "provenance", List.of(provEntry("value", "https://x.example/a", Confidence.MEDIUM)));
    var result = SubmitValidation.validate(envelope, schema);
    var rej = assertInstanceOf(SubmitValidation.Result.Rejected.class, result);
    assertTrue(rej.error().contains("count"));
  }

  @Test
  void provenancedSchemaRejectsUnknownProvenanceField() {
    var schema = OutputSchema.provenancedOf(Answer.class);
    var envelope = new HashMap<String, Object>();
    envelope.put("output", output("hi", 3));
    envelope.put(
        "provenance",
        List.of(
            provEntry("value", "https://x.example/a", Confidence.MEDIUM),
            provEntry("count", "https://x.example/b", Confidence.MEDIUM),
            provEntry("nope", "https://x.example/c", Confidence.MEDIUM)));
    var result = SubmitValidation.validate(envelope, schema);
    var rej = assertInstanceOf(SubmitValidation.Result.Rejected.class, result);
    assertTrue(rej.error().contains("nope"));
  }

  @Test
  void toJsonRendersMapsCleanly() {
    assertEquals("{\"a\":1}", SubmitValidation.toJson(Map.of("a", 1)));
  }

  @Test
  void toJsonRendersRecord() {
    var json = SubmitValidation.toJson(new Answer("x", 5));
    assertTrue(json.contains("\"value\":\"x\""));
    assertTrue(json.contains("\"count\":5"));
  }

  private static Map<String, Object> provEntry(String field, String url, Confidence c) {
    var entry = new HashMap<String, Object>();
    entry.put("field", field);
    entry.put("sources", List.of(Map.of("url", url, "excerpts", List.of("see"))));
    entry.put("reasoning", "because " + field);
    entry.put("confidence", c.name());
    return entry;
  }
}
