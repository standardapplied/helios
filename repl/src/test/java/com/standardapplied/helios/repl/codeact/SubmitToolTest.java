/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Confidence;
import ai.singlr.core.common.Provenanced;
import ai.singlr.core.common.SubmitValidator;
import ai.singlr.core.common.ValidationResult;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.ToolContext;
import ai.singlr.session.tools.ToolCategory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SubmitToolTest {

  public record Answer(String value, int count) {}

  private static Map<String, Object> output(String value, int count) {
    var m = new HashMap<String, Object>();
    m.put("value", value);
    m.put("count", count);
    return m;
  }

  private static Map<String, Object> provenanceEntry(
      String field, String url, Confidence confidence) {
    var entry = new HashMap<String, Object>();
    entry.put("field", field);
    entry.put("sources", List.of(Map.of("url", url, "excerpts", List.of("see " + field))));
    entry.put("reasoning", "because " + field);
    entry.put("confidence", confidence.name());
    return entry;
  }

  private static Map<String, Object> provenancedEnvelope(
      Map<String, Object> outputMap, List<Map<String, Object>> provenance) {
    var env = new HashMap<String, Object>();
    env.put("output", outputMap);
    env.put("provenance", provenance);
    return env;
  }

  // ── construction ────────────────────────────────────────────────────────

  @Test
  void rejectsNullSchema() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> SubmitTool.create(null, new SubmittedValueHolder()));
    assertEquals("schema must not be null", ex.getMessage());
  }

  @Test
  void rejectsNullHolder() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> SubmitTool.create(OutputSchema.of(Answer.class), null));
    assertEquals("holder must not be null", ex.getMessage());
  }

  @Test
  void toolHasStableNameAndOutputParameter() {
    var tool = SubmitTool.create(OutputSchema.of(Answer.class), new SubmittedValueHolder());
    assertEquals(SubmitTool.NAME, tool.name());
    assertEquals(1, tool.parameters().size());
    assertEquals("output", tool.parameters().get(0).name());
    assertTrue(tool.parameters().get(0).required());
  }

  @Test
  void bindingCategorisesAsControl() {
    var binding = SubmitTool.binding(OutputSchema.of(Answer.class), new SubmittedValueHolder());
    assertSame(ToolCategory.CONTROL, binding.category());
    assertEquals(SubmitTool.NAME, binding.tool().name());
  }

  // ── success path ────────────────────────────────────────────────────────

  @Test
  void acceptsAValueMatchingTheSchema() {
    var holder = new SubmittedValueHolder();
    var tool = SubmitTool.create(OutputSchema.of(Answer.class), holder);
    var result = tool.execute(Map.of("output", output("hello", 3)), ToolContext.noop());
    assertTrue(result.success());
    assertTrue(holder.isSubmitted());
    var stored = holder.peek().orElseThrow();
    assertInstanceOf(Map.class, stored);
    @SuppressWarnings("unchecked")
    var asMap = (Map<String, Object>) stored;
    assertEquals("hello", asMap.get("value"));
    assertEquals(3, asMap.get("count"));
  }

  @Test
  void secondSubmitAfterAcceptIsRejected() {
    var holder = new SubmittedValueHolder();
    var tool = SubmitTool.create(OutputSchema.of(Answer.class), holder);
    tool.execute(Map.of("output", output("first", 1)), ToolContext.noop());
    var second = tool.execute(Map.of("output", output("second", 2)), ToolContext.noop());
    assertFalse(second.success());
    assertTrue(second.output().contains("prior submit"));
  }

  // ── validation failures ─────────────────────────────────────────────────

  @Test
  void missingOutputParameterFails() {
    var tool = SubmitTool.create(OutputSchema.of(Answer.class), new SubmittedValueHolder());
    var result = tool.execute(Map.of(), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("'output' is required"));
  }

  @Test
  void schemaMismatchFails() {
    var holder = new SubmittedValueHolder();
    var tool = SubmitTool.create(OutputSchema.of(Answer.class), holder);
    var bad = new HashMap<String, Object>();
    bad.put("value", "hi");
    var result = tool.execute(Map.of("output", bad), ToolContext.noop());
    assertFalse(result.success());
    var msg = result.output();
    assertTrue(msg.contains("Submit validation failed"));
    assertTrue(msg.contains("count"));
    assertTrue(holder.peek().isEmpty(), "schema failure must not store a value");
  }

  // ── submitValidator path ────────────────────────────────────────────────

  @Test
  void submitValidatorFailureSurfacesCorrection() {
    var holder = new SubmittedValueHolder();
    var schema =
        OutputSchema.of(Answer.class)
            .withSubmitValidator((Answer a) -> a.count() > 0, "count must be positive");
    var tool = SubmitTool.create(schema, holder);
    var result = tool.execute(Map.of("output", output("oops", 0)), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("count must be positive"));
    assertTrue(holder.peek().isEmpty());
  }

  @Test
  void submitValidatorThrowReturnsValidationFailure() {
    var holder = new SubmittedValueHolder();
    SubmitValidator<Answer> noisy =
        a -> {
          throw new RuntimeException("operator bug");
        };
    var schema = OutputSchema.of(Answer.class).withSubmitValidator(noisy);
    var tool = SubmitTool.create(schema, holder);
    var result = tool.execute(Map.of("output", output("ok", 1)), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("submit validator threw"));
  }

  @Test
  void submitValidatorNullReturnReportsAsFailure() {
    var holder = new SubmittedValueHolder();
    SubmitValidator<Answer> nullish = a -> null;
    var schema = OutputSchema.of(Answer.class).withSubmitValidator(nullish);
    var tool = SubmitTool.create(schema, holder);
    var result = tool.execute(Map.of("output", output("ok", 1)), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("validator returned null"));
  }

  @Test
  void submitValidatorSuccessStoresTypedValue() {
    var holder = new SubmittedValueHolder();
    SubmitValidator<Answer> ok = a -> ValidationResult.success();
    var schema = OutputSchema.of(Answer.class).withSubmitValidator(ok);
    var tool = SubmitTool.create(schema, holder);
    var result = tool.execute(Map.of("output", output("done", 2)), ToolContext.noop());
    assertTrue(result.success());
    var stored = holder.peek().orElseThrow();
    assertInstanceOf(Answer.class, stored);
    assertEquals(new Answer("done", 2), stored);
  }

  @Test
  void submitValidatorParseFailureReportsClearly() {
    var holder = new SubmittedValueHolder();
    SubmitValidator<Answer> ok = a -> ValidationResult.success();
    var schema = OutputSchema.of(Answer.class).withSubmitValidator(ok);
    var tool = SubmitTool.create(schema, holder);
    var bad = new HashMap<String, Object>();
    bad.put("value", "x");
    bad.put("count", "not-a-number");
    var result = tool.execute(Map.of("output", bad), ToolContext.noop());
    assertFalse(result.success());
  }

  // ── provenanced path ────────────────────────────────────────────────────

  @Test
  void provenancedSubmitReconstructsTypedValue() {
    var holder = new SubmittedValueHolder();
    var schema = OutputSchema.provenancedOf(Answer.class);
    var tool = SubmitTool.create(schema, holder);
    var envelope =
        provenancedEnvelope(
            output("answer", 1),
            List.of(
                provenanceEntry("value", "https://example.test/value", Confidence.HIGH),
                provenanceEntry("count", "https://example.test/count", Confidence.HIGH)));
    var result = tool.execute(Map.of("output", envelope), ToolContext.noop());
    assertTrue(result.success());
    var stored = holder.peek().orElseThrow();
    assertInstanceOf(Provenanced.class, stored);
    @SuppressWarnings("unchecked")
    var typed = (Provenanced<Answer>) stored;
    assertNotNull(typed.output());
    assertEquals(2, typed.provenance().size());
  }

  @Test
  void provenancedSubmitRejectsMissingEntry() {
    var holder = new SubmittedValueHolder();
    var schema = OutputSchema.provenancedOf(Answer.class);
    var tool = SubmitTool.create(schema, holder);
    var envelope =
        provenancedEnvelope(
            output("answer", 1),
            List.of(provenanceEntry("value", "https://example.test/value", Confidence.HIGH)));
    var result = tool.execute(Map.of("output", envelope), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("missing provenance entry for output"));
  }

  @Test
  void provenancedSubmitRejectsUnknownField() {
    var holder = new SubmittedValueHolder();
    var schema = OutputSchema.provenancedOf(Answer.class);
    var tool = SubmitTool.create(schema, holder);
    var envelope =
        provenancedEnvelope(
            output("answer", 1),
            List.of(
                provenanceEntry("value", "https://example.test/value", Confidence.HIGH),
                provenanceEntry("count", "https://example.test/count", Confidence.HIGH),
                provenanceEntry("ghost", "https://example.test/ghost", Confidence.HIGH)));
    var result = tool.execute(Map.of("output", envelope), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("references unknown field 'ghost'"));
  }

  @Test
  void provenancedSubmitRejectsLowConfidenceMissingSourceViaDefaultValidator() {
    var holder = new SubmittedValueHolder();
    var schema = OutputSchema.provenancedOf(Answer.class);
    var tool = SubmitTool.create(schema, holder);
    var noSourcesEntry = new HashMap<String, Object>();
    noSourcesEntry.put("field", "value");
    noSourcesEntry.put("sources", List.of());
    noSourcesEntry.put("reasoning", "no sources but HIGH");
    noSourcesEntry.put("confidence", "HIGH");
    var envelope =
        provenancedEnvelope(
            output("answer", 1),
            List.of(noSourcesEntry, provenanceEntry("count", "u", Confidence.HIGH)));
    var result = tool.execute(Map.of("output", envelope), ToolContext.noop());
    assertFalse(result.success());
  }

  @Test
  void provenancedSubmitRejectsDuplicateField() {
    var holder = new SubmittedValueHolder();
    var schema = OutputSchema.provenancedOf(Answer.class);
    var tool = SubmitTool.create(schema, holder);
    var envelope =
        provenancedEnvelope(
            output("answer", 1),
            List.of(
                provenanceEntry("value", "https://example.test/v1", Confidence.HIGH),
                provenanceEntry("value", "https://example.test/v2", Confidence.HIGH),
                provenanceEntry("count", "https://example.test/c", Confidence.HIGH)));
    var result = tool.execute(Map.of("output", envelope), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("duplicate provenance entry"));
  }

  @Test
  void provenancedSubmitRunsSubmitValidatorWithTypedProvenancedValue() {
    var holder = new SubmittedValueHolder();
    SubmitValidator<Provenanced<Answer>> ok = p -> ValidationResult.success();
    var schema = OutputSchema.provenancedOf(Answer.class).withSubmitValidator(ok);
    var tool = SubmitTool.create(schema, holder);
    var envelope =
        provenancedEnvelope(
            output("answer", 1),
            List.of(
                provenanceEntry("value", "https://example.test/value", Confidence.HIGH),
                provenanceEntry("count", "https://example.test/count", Confidence.HIGH)));
    var result = tool.execute(Map.of("output", envelope), ToolContext.noop());
    assertTrue(result.success(), result.output());
    var stored = holder.peek().orElseThrow();
    assertInstanceOf(Provenanced.class, stored);
  }

  @Test
  void provenancedSubmitWithFailingSubmitValidatorReportsCorrection() {
    var holder = new SubmittedValueHolder();
    SubmitValidator<Provenanced<Answer>> bad = p -> ValidationResult.failure("nope, override");
    var schema = OutputSchema.provenancedOf(Answer.class).withSubmitValidator(bad);
    var tool = SubmitTool.create(schema, holder);
    var envelope =
        provenancedEnvelope(
            output("answer", 1),
            List.of(
                provenanceEntry("value", "https://example.test/value", Confidence.HIGH),
                provenanceEntry("count", "https://example.test/count", Confidence.HIGH)));
    var result = tool.execute(Map.of("output", envelope), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("nope, override"));
    assertTrue(holder.peek().isEmpty());
  }

  @Test
  void provenancedSubmitRejectsNonObjectOutputField() {
    var holder = new SubmittedValueHolder();
    var schema = OutputSchema.provenancedOf(Answer.class);
    var tool = SubmitTool.create(schema, holder);
    var env = new HashMap<String, Object>();
    env.put("output", "not-a-map");
    env.put("provenance", List.of());
    var result = tool.execute(Map.of("output", env), ToolContext.noop());
    assertFalse(result.success());
  }

  @Test
  void provenancedSubmitRejectsNonEnvelopeOutput() {
    var holder = new SubmittedValueHolder();
    var schema = OutputSchema.provenancedOf(Answer.class);
    var tool = SubmitTool.create(schema, holder);
    var result = tool.execute(Map.of("output", "not an envelope"), ToolContext.noop());
    assertFalse(result.success());
  }
}
