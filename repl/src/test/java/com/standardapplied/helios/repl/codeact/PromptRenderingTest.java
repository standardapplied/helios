/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.repl.codeact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.schema.JsonSchema;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.repl.host.HostFunction;
import com.standardapplied.helios.repl.host.HostParameter;
import com.standardapplied.helios.repl.sandbox.SandboxPrelude;
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
        JsonSchema.object()
            .withProperty("required", JsonSchema.string("required field"), true)
            .withProperty("optional", JsonSchema.string("optional field"))
            .build();
    var sb = new StringBuilder();
    PromptRendering.appendFields(sb, new OutputSchema<>(Map.class, schema, null, null, null));
    var rendered = sb.toString();
    assertTrue(rendered.contains("required (String) — required field"));
    assertTrue(rendered.contains("optional (String) [optional] — optional field"));
  }

  @Test
  void appendFieldsHandlesAnOutputSchemaWithoutAJsonSchema() {
    var sb = new StringBuilder();
    PromptRendering.appendFields(sb, new OutputSchema<>(Map.class, null, null, null, null));
    assertEquals("", sb.toString());
  }

  @Test
  void appendFieldsWithoutARequiredListMarksEveryFieldOptional() {
    var schema = JsonSchema.object().withProperty("topic", JsonSchema.string()).build();
    var sb = new StringBuilder();
    PromptRendering.appendFields(sb, new OutputSchema<>(Map.class, schema, null, null, null));
    assertEquals("  - topic (String) [optional]\n", sb.toString());
  }

  @Test
  void appendCustomHostFunctionsRendersTheHeaderOnceAndMarksOptionalParameters() {
    var quote =
        new HostFunction(
            "marketQuote",
            "Looks up a market price",
            List.of(
                HostParameter.required("ticker", ParameterType.STRING, "Symbol"),
                HostParameter.optional("venue", ParameterType.STRING, "Exchange")),
            params -> "x");
    var ping = new HostFunction("ping", "Checks the host", params -> "pong");
    var sb = new StringBuilder();
    PromptRendering.appendCustomHostFunctions(sb, List.of(quote, ping));
    assertEquals(
        "\nCustom host functions registered for this run:\n"
            + "  - "
            + SandboxPrelude.formatSignature(quote)
            + " — Looks up a market price\n"
            + "      ticker (string) — Symbol\n"
            + "      [optional] venue (string) — Exchange\n"
            + "  - "
            + SandboxPrelude.formatSignature(ping)
            + " — Checks the host\n",
        sb.toString());
  }

  @Test
  void describeReturnsAnyForASchemaWithoutAType() {
    var schema = new JsonSchema(null, null, null, null, null, null, null, null);
    assertEquals("any", PromptRendering.describe(schema));
  }

  @Test
  void describeRendersAStringWithAnEmptyEnumAsString() {
    var schema = new JsonSchema("string", null, null, null, List.of(), null, null, null);
    assertEquals("String", PromptRendering.describe(schema));
  }

  @Test
  void appendFieldsWithoutDescriptionsOmitsEmDash() {
    var schema = JsonSchema.object().withProperty("topic", JsonSchema.string(), true).build();
    var sb = new StringBuilder();
    PromptRendering.appendFields(sb, new OutputSchema<>(Map.class, schema, null, null, null));
    assertFalse(sb.toString().contains("—"));
  }
}
