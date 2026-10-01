/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Confidence;
import ai.singlr.core.common.ProvenanceValidator;
import ai.singlr.core.common.Provenanced;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutputSchemaProvenancedTest {

  private record Pick(String name, String reason) {}

  @Test
  void plainOfHasNullValidatorAndInnerType() {
    var schema = OutputSchema.of(Pick.class);
    assertNull(schema.provenanceValidator());
    assertNull(schema.innerOutputType());
    assertEquals(Pick.class, schema.type());
  }

  @Test
  void ofWithExplicitSchema() {
    var jsonSchema = JsonSchema.object().build();
    var schema = OutputSchema.of(Pick.class, jsonSchema);
    assertEquals(Pick.class, schema.type());
    assertSame(jsonSchema, schema.schema());
    assertNull(schema.provenanceValidator());
    assertNull(schema.innerOutputType());
    assertNull(schema.submitValidator());
  }

  @Test
  void provenancedOfPopulatesValidatorAndInnerType() {
    var schema = OutputSchema.provenancedOf(Pick.class);
    assertSame(ProvenanceValidator.DEFAULT, schema.provenanceValidator());
    assertEquals(Pick.class, schema.innerOutputType());
    assertEquals(Provenanced.class, schema.type());
  }

  @Test
  void provenancedOfBuildsEnvelopeSchema() {
    var schema = OutputSchema.provenancedOf(Pick.class).schema();
    assertEquals("object", schema.type());
    assertNotNull(schema.properties().get("output"));
    assertNotNull(schema.properties().get("provenance"));
    assertEquals(List.of("output", "provenance"), schema.required());
  }

  @Test
  void provenancedSchemaProvenanceIsArrayOfFieldEntries() {
    var schema = OutputSchema.provenancedOf(Pick.class).schema();
    var provArray = schema.properties().get("provenance");
    assertEquals("array", provArray.type());
    var entrySchema = provArray.items();
    assertEquals("object", entrySchema.type());
    assertTrue(entrySchema.required().contains("field"));
    assertTrue(entrySchema.required().contains("sources"));
    assertTrue(entrySchema.required().contains("reasoning"));
    assertTrue(entrySchema.required().contains("confidence"));
  }

  @Test
  void provenancedSchemaConfidenceIsEnum() {
    var schema = OutputSchema.provenancedOf(Pick.class).schema();
    var entrySchema = schema.properties().get("provenance").items();
    var confSchema = entrySchema.properties().get("confidence");
    assertEquals(List.of("LOW", "MEDIUM", "HIGH"), confSchema.enumValues());
  }

  @Test
  void provenancedSchemaSourceUrlIsRequiredButTitleIsNot() {
    var schema = OutputSchema.provenancedOf(Pick.class).schema();
    var sourcesSchema = schema.properties().get("provenance").items().properties().get("sources");
    var sourceItem = sourcesSchema.items();
    assertTrue(sourceItem.required().contains("url"));
    assertTrue(sourceItem.required().contains("excerpts"));
    assertEquals(2, sourceItem.required().size());
  }

  @Test
  void provenancedOfWithCustomValidator() {
    ProvenanceValidator strict = e -> ai.singlr.core.common.ValidationResult.success();
    var schema = OutputSchema.provenancedOf(Pick.class, strict);
    assertSame(strict, schema.provenanceValidator());
  }

  @Test
  void provenancedOfRejectsNullClass() {
    assertThrows(IllegalArgumentException.class, () -> OutputSchema.provenancedOf(null));
  }

  @Test
  void provenancedOfRejectsNullValidator() {
    assertThrows(
        IllegalArgumentException.class, () -> OutputSchema.provenancedOf(Pick.class, null));
  }

  @Test
  void reconstructProvenancedFromMap() {
    Map<String, Object> raw =
        Map.of(
            "output",
            Map.of("name", "alice", "reason", "fits the role"),
            "provenance",
            List.of(
                Map.of(
                    "field", "name",
                    "sources", List.of(Map.of("url", "https://x.com", "excerpts", List.of("a"))),
                    "reasoning", "she said her name",
                    "confidence", "HIGH"),
                Map.of(
                    "field", "reason",
                    "sources", List.of(),
                    "reasoning", "her stated goals match",
                    "confidence", "LOW")));

    var result =
        OutputSchema.reconstructProvenanced(
            raw,
            outputMap -> {
              @SuppressWarnings("unchecked")
              var m = (Map<String, Object>) outputMap;
              return new Pick((String) m.get("name"), (String) m.get("reason"));
            });

    assertEquals("alice", result.output().name());
    assertEquals(2, result.provenance().size());
    assertEquals(Confidence.HIGH, result.forField("name").confidence());
    assertEquals(Confidence.LOW, result.forField("reason").confidence());
    assertEquals(1, result.forField("name").sources().size());
    assertEquals("https://x.com", result.forField("name").sources().getFirst().url());
  }

  @Test
  void reconstructAcceptsLowerCaseConfidenceFromModel() {
    Map<String, Object> raw =
        Map.of(
            "output",
            Map.of("name", "alice", "reason", "..."),
            "provenance",
            List.of(
                Map.of(
                    "field", "name",
                    "sources", List.of(),
                    "reasoning", "guess",
                    "confidence", "low")));
    var result =
        OutputSchema.reconstructProvenanced(
            raw,
            o -> {
              @SuppressWarnings("unchecked")
              var m = (Map<String, Object>) o;
              return new Pick((String) m.get("name"), (String) m.get("reason"));
            });
    assertEquals(Confidence.LOW, result.forField("name").confidence());
  }

  @Test
  void reconstructRejectsNullMap() {
    assertThrows(
        IllegalArgumentException.class, () -> OutputSchema.reconstructProvenanced(null, o -> o));
  }

  @Test
  void reconstructRejectsNullConverter() {
    assertThrows(
        IllegalArgumentException.class,
        () -> OutputSchema.reconstructProvenanced(Map.of("output", Map.of()), null));
  }

  @Test
  void reconstructRejectsMissingOutput() {
    assertThrows(
        IllegalArgumentException.class,
        () -> OutputSchema.reconstructProvenanced(Map.of("provenance", List.of()), o -> o));
  }

  @Test
  void reconstructRejectsNonArrayProvenance() {
    Map<String, Object> raw = Map.of("output", Map.of(), "provenance", "not-an-array");
    assertThrows(
        IllegalArgumentException.class, () -> OutputSchema.reconstructProvenanced(raw, o -> "x"));
  }

  @Test
  void reconstructRejectsNonObjectProvenanceEntry() {
    Map<String, Object> raw = Map.of("output", Map.of(), "provenance", List.of("not-an-object"));
    assertThrows(
        IllegalArgumentException.class, () -> OutputSchema.reconstructProvenanced(raw, o -> "x"));
  }

  @Test
  void reconstructHandlesNonListExcerpts() {
    Map<String, Object> raw =
        Map.of(
            "output",
            Map.of("name", "x", "reason", "y"),
            "provenance",
            List.of(
                Map.of(
                    "field", "name",
                    "sources",
                        List.of(
                            Map.of(
                                "url", "https://x.com",
                                "excerpts", "not-a-list-but-we-tolerate-it")),
                    "reasoning", "ok",
                    "confidence", "MEDIUM")));
    var result =
        OutputSchema.reconstructProvenanced(
            raw,
            o -> {
              @SuppressWarnings("unchecked")
              var m = (Map<String, Object>) o;
              return new Pick((String) m.get("name"), (String) m.get("reason"));
            });
    assertTrue(result.forField("name").sources().getFirst().excerpts().isEmpty());
  }

  @Test
  void withSubmitValidatorAttachesValidator() {
    var schema = OutputSchema.of(Pick.class);
    assertNull(schema.submitValidator());
    var withValidator =
        schema.withSubmitValidator(p -> ai.singlr.core.common.ValidationResult.success());
    assertNotNull(withValidator.submitValidator());
    assertNull(schema.submitValidator(), "original schema must not be mutated");
  }

  @Test
  void withSubmitValidatorRejectsNullValidator() {
    var schema = OutputSchema.of(Pick.class);
    assertThrows(
        IllegalArgumentException.class,
        () -> schema.withSubmitValidator((ai.singlr.core.common.SubmitValidator<Pick>) null));
  }

  @Test
  void withSubmitValidatorPredicateOverloadWraps() {
    var schema =
        OutputSchema.of(Pick.class).withSubmitValidator(p -> !p.name().isEmpty(), "name required");
    var v = schema.submitValidator();
    assertTrue(v.validate(new Pick("alice", "fits")).ok());
    var failure = v.validate(new Pick("", "fits"));
    assertEquals("name required", failure.message());
  }

  @Test
  void withSubmitValidatorPredicateRejectsNullPredicate() {
    var schema = OutputSchema.of(Pick.class);
    assertThrows(
        IllegalArgumentException.class,
        () -> schema.withSubmitValidator((java.util.function.Predicate<Pick>) null, "msg"));
  }

  @Test
  void withSubmitValidatorPredicateRejectsBlankCorrection() {
    var schema = OutputSchema.of(Pick.class);
    assertThrows(IllegalArgumentException.class, () -> schema.withSubmitValidator(p -> true, ""));
    assertThrows(IllegalArgumentException.class, () -> schema.withSubmitValidator(p -> true, null));
  }

  @Test
  void withSubmitValidatorPreservesProvenanceFields() {
    var schema =
        OutputSchema.provenancedOf(Pick.class)
            .withSubmitValidator(p -> ai.singlr.core.common.ValidationResult.success());
    assertNotNull(schema.provenanceValidator());
    assertEquals(Pick.class, schema.innerOutputType());
    assertNotNull(schema.submitValidator());
  }

  @Test
  void reconstructHandlesMissingSourcesAsEmptyList() {
    Map<String, Object> raw =
        Map.of(
            "output",
            Map.of("name", "x", "reason", "y"),
            "provenance",
            List.of(
                Map.of(
                    "field", "name",
                    "reasoning", "guess",
                    "confidence", "LOW")));
    var result =
        OutputSchema.reconstructProvenanced(
            raw,
            o -> {
              @SuppressWarnings("unchecked")
              var m = (Map<String, Object>) o;
              return new Pick((String) m.get("name"), (String) m.get("reason"));
            });
    assertTrue(result.forField("name").sources().isEmpty());
  }
}
