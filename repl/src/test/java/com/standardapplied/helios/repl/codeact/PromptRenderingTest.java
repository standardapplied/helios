/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.schema.JsonSchema;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.host.HostParameter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for the package-private {@link PromptRendering} helpers. The two strategy
 * prompts (CodeAct / RLM) flow through these primitives; locking the field-rendering and
 * host-function-rendering output here means a future change to either prompt is forced to
 * acknowledge what the model sees.
 */
final class PromptRenderingTest {

  public record Input(String topic, int count) {}

  @Test
  void appendFieldsHandlesNullSchemaGracefully() {
    var sb = new StringBuilder();
    PromptRendering.appendFields(sb, null);
    assertEquals("", sb.toString());
  }

  @Test
  void appendFieldsHandlesSchemaWithNullProperties() {
    var sb = new StringBuilder();
    var leafSchema = OutputSchema.of(String.class, JsonSchema.string());
    PromptRendering.appendFields(sb, leafSchema);
    assertEquals("", sb.toString(), "leaf schemas without properties must render nothing");
  }

  @Test
  void appendFieldsRendersEachTopLevelFieldOnItsOwnLine() {
    var sb = new StringBuilder();
    PromptRendering.appendFields(sb, OutputSchema.of(Input.class));
    var rendered = sb.toString();
    assertTrue(rendered.contains("topic"));
    assertTrue(rendered.contains("count"));
    assertTrue(rendered.contains("String"));
    assertTrue(rendered.contains("int"));
  }

  @Test
  void appendCustomHostFunctionsSkipsReservedNames() {
    var fn =
        new HostFunction(
            "predict",
            "framework predict",
            List.of(HostParameter.required("instructions", ParameterType.STRING, "Sys")),
            params -> "ignored");
    var sb = new StringBuilder();
    PromptRendering.appendCustomHostFunctions(sb, List.of(fn));
    assertEquals("", sb.toString(), "reserved host function names must not render");
  }

  @Test
  void appendCustomHostFunctionsRendersNonReservedFunctions() {
    var fn =
        new HostFunction(
            "marketQuote",
            "Looks up a market price",
            List.of(HostParameter.required("ticker", ParameterType.STRING, "Symbol")),
            params -> Map.of("output", "x"));
    var sb = new StringBuilder();
    PromptRendering.appendCustomHostFunctions(sb, List.of(fn));
    var out = sb.toString();
    assertTrue(out.contains("Custom host functions registered for this run"));
    assertTrue(out.contains("marketQuote"));
    assertTrue(out.contains("ticker"));
    assertTrue(out.contains("Looks up a market price"));
  }

  @Test
  void appendCustomHostFunctionsNoopWhenListIsEmptyOrNull() {
    var sb = new StringBuilder();
    PromptRendering.appendCustomHostFunctions(sb, null);
    PromptRendering.appendCustomHostFunctions(sb, List.of());
    assertEquals("", sb.toString());
  }

  @Test
  void describeRendersJsonSchemaTypesAsHumanLabels() {
    assertEquals("String", PromptRendering.describe(JsonSchema.string()));
    assertEquals("int", PromptRendering.describe(JsonSchema.integer()));
    assertEquals("number", PromptRendering.describe(JsonSchema.number()));
    assertEquals("boolean", PromptRendering.describe(JsonSchema.bool()));
    assertEquals("List<String>", PromptRendering.describe(JsonSchema.array(JsonSchema.string())));
    assertEquals("object", PromptRendering.describe(JsonSchema.map(JsonSchema.string())));
    assertEquals("enum [a, b]", PromptRendering.describe(JsonSchema.enumOf(List.of("a", "b"))));
  }

  @Test
  void describeReturnsAnyForNull() {
    assertEquals("any", PromptRendering.describe(null));
  }

  @Test
  void describeReturnsRawTypeForUnknown() {
    var schema = new JsonSchema("custom", null, null, null, null, null, null, null);
    assertEquals("custom", PromptRendering.describe(schema));
  }

  @Test
  void describeRendersArrayWithoutItemsAsListOfAny() {
    var schema = new JsonSchema("array", null, null, null, null, null, null, null);
    assertEquals("List<any>", PromptRendering.describe(schema));
  }

  @Test
  void appendFieldsOptionalFieldGetsOptionalMarker() {
    // OutputSchema.of with a record marks all fields as required. We construct a JsonSchema
    // manually to exercise the optional branch without depending on schema-of semantics.
    var schema =
        new JsonSchema(
            "object",
            Map.of(
                "required", JsonSchema.string("required field"),
                "optional", JsonSchema.string("optional field")),
            null,
            List.of("required"),
            null,
            null,
            null,
            null);
    var sb = new StringBuilder();
    PromptRendering.appendFields(sb, new OutputSchema<>(Map.class, schema, null, null, null));
    var rendered = sb.toString();
    assertTrue(rendered.contains("required (String) — required field"));
    assertTrue(rendered.contains("optional (String) [optional] — optional field"));
  }

  @Test
  void appendFieldsWithoutDescriptionsOmitsEmDash() {
    var schema =
        new JsonSchema(
            "object",
            Map.of("topic", JsonSchema.string()),
            null,
            List.of("topic"),
            null,
            null,
            null,
            null);
    var sb = new StringBuilder();
    PromptRendering.appendFields(sb, new OutputSchema<>(Map.class, schema, null, null, null));
    assertFalse(sb.toString().contains("—"));
  }
}
