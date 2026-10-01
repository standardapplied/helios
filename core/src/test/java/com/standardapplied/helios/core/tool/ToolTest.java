/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolTest {

  @Test
  void defaultResultCompactorReturnsConstantPlaceholder() {
    var tool =
        Tool.newBuilder()
            .withName("noop")
            .withDescription("noop")
            .withExecutor((args, ctx) -> ToolResult.success(""))
            .build();
    assertNotNull(tool.resultCompactor());
    assertEquals("[result omitted]", tool.resultCompactor().apply("anything"));
    assertEquals("[result omitted]", tool.resultCompactor().apply(""));
  }

  @Test
  void customResultCompactorPreservedThroughBuilder() {
    var tool =
        Tool.newBuilder()
            .withName("custom")
            .withDescription("custom")
            .withExecutor((args, ctx) -> ToolResult.success(""))
            .withResultCompactor(content -> "[shortened: " + content.length() + " chars]")
            .build();
    assertEquals("[shortened: 5 chars]", tool.resultCompactor().apply("hello"));
  }

  @Test
  void nullCompactorResetsToDefault() {
    var tool =
        Tool.newBuilder()
            .withName("reset")
            .withDescription("reset")
            .withExecutor((args, ctx) -> ToolResult.success(""))
            .withResultCompactor(content -> "custom")
            .withResultCompactor(null)
            .build();
    assertEquals(
        "[result omitted]",
        tool.resultCompactor().apply("anything"),
        "passing null to withResultCompactor must restore the default, not break the tool");
  }

  @Test
  void canonicalCtorCoercesNullCompactorToDefault() {
    var tool =
        new Tool("name", "desc", List.of(), (args, ctx) -> ToolResult.success(""), false, null);
    assertEquals("[result omitted]", tool.resultCompactor().apply("x"));
  }

  @Test
  void legacyFiveArgConstructorUsesDefaultCompactor() {
    // Pre-1.3 callers that constructed Tool via the canonical record constructor used a 5-arg
    // shape. The convenience ctor must keep working and supply the default compactor.
    var tool = new Tool("legacy", "legacy", List.of(), (args, ctx) -> ToolResult.success(""), true);
    assertEquals(Tool.DEFAULT_RESULT_COMPACTOR, tool.resultCompactor());
    assertTrue(tool.idempotent());
  }

  @Test
  void buildSimpleTool() {
    var tool =
        Tool.newBuilder()
            .withName("greet")
            .withDescription("Greet a person")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("name")
                    .withType(ParameterType.STRING)
                    .withDescription("The name to greet")
                    .withRequired(true)
                    .build())
            .withExecutor(
                (args, ctx) -> {
                  var name = (String) args.get("name");
                  return ToolResult.success("Hello, " + name + "!");
                })
            .build();

    assertEquals("greet", tool.name());
    assertEquals("Greet a person", tool.description());
    assertEquals(1, tool.parameters().size());
    assertEquals(List.of("name"), tool.requiredParameters());
  }

  @Test
  void executeTool() {
    var tool =
        Tool.newBuilder()
            .withName("add")
            .withDescription("Add two numbers")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("a")
                    .withType(ParameterType.INTEGER)
                    .withRequired(true)
                    .build())
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("b")
                    .withType(ParameterType.INTEGER)
                    .withRequired(true)
                    .build())
            .withExecutor(
                (args, ctx) -> {
                  var a = ((Number) args.get("a")).intValue();
                  var b = ((Number) args.get("b")).intValue();
                  return ToolResult.success(String.valueOf(a + b));
                })
            .build();

    var result = tool.execute(Map.of("a", 5, "b", 3));

    assertTrue(result.success());
    assertEquals("8", result.output());
  }

  @Test
  void executeToolWithError() {
    var tool =
        Tool.newBuilder()
            .withName("fail")
            .withDescription("A tool that fails")
            .withExecutor(
                (args, ctx) -> {
                  throw new RuntimeException("Intentional failure");
                })
            .build();

    var result = tool.execute(Map.of());

    assertFalse(result.success());
    assertTrue(result.output().contains("Tool execution failed"));
  }

  @Test
  void toolWithNoExecutorThrowsAtBuild() {
    assertThrows(
        IllegalStateException.class,
        () -> Tool.newBuilder().withName("noop").withDescription("No executor").build());
  }

  @Test
  void toolWithNoNameThrowsAtBuild() {
    assertThrows(
        IllegalStateException.class,
        () -> Tool.newBuilder().withExecutor((args, ctx) -> ToolResult.success("ok")).build());
  }

  @Test
  void toolWithBlankNameThrowsAtBuild() {
    assertThrows(
        IllegalStateException.class,
        () ->
            Tool.newBuilder()
                .withName("  ")
                .withExecutor((args, ctx) -> ToolResult.success("ok"))
                .build());
  }

  @Test
  void parametersAsJsonSchema() {
    var tool =
        Tool.newBuilder()
            .withName("search")
            .withDescription("Search for items")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("query")
                    .withType(ParameterType.STRING)
                    .withDescription("Search query")
                    .withRequired(true)
                    .build())
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("limit")
                    .withType(ParameterType.INTEGER)
                    .withDescription("Max results")
                    .withRequired(false)
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();

    var schema = tool.parametersAsJsonSchema();

    assertEquals("object", schema.get("type"));

    @SuppressWarnings("unchecked")
    var properties = (Map<String, Object>) schema.get("properties");
    assertTrue(properties.containsKey("query"));
    assertTrue(properties.containsKey("limit"));

    @SuppressWarnings("unchecked")
    var required = (List<String>) schema.get("required");
    assertEquals(List.of("query"), required);
  }

  @Test
  void arrayParameter() {
    var tool =
        Tool.newBuilder()
            .withName("process")
            .withDescription("Process items")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("items")
                    .withType(ParameterType.ARRAY)
                    .withDescription("Items to process")
                    .withItems(ToolParameter.newBuilder().withType(ParameterType.STRING).build())
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();

    var schema = tool.parametersAsJsonSchema();

    @SuppressWarnings("unchecked")
    var properties = (Map<String, Object>) schema.get("properties");
    @SuppressWarnings("unchecked")
    var itemsSchema = (Map<String, Object>) properties.get("items");

    assertEquals("array", itemsSchema.get("type"));
    assertNotNull(itemsSchema.get("items"));
  }

  @Test
  void parameterWithNoDescription() {
    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("Test tool")
            .withParameter(
                ToolParameter.newBuilder().withName("value").withType(ParameterType.STRING).build())
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();

    var schema = tool.parametersAsJsonSchema();

    @SuppressWarnings("unchecked")
    var properties = (Map<String, Object>) schema.get("properties");
    @SuppressWarnings("unchecked")
    var valueSchema = (Map<String, Object>) properties.get("value");

    assertEquals("string", valueSchema.get("type"));
    assertFalse(valueSchema.containsKey("description"));
  }

  @Test
  void arrayWithItemDescription() {
    var tool =
        Tool.newBuilder()
            .withName("process")
            .withDescription("Process items")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("items")
                    .withType(ParameterType.ARRAY)
                    .withItems(
                        ToolParameter.newBuilder()
                            .withType(ParameterType.STRING)
                            .withDescription("Item description")
                            .build())
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();

    var schema = tool.parametersAsJsonSchema();

    @SuppressWarnings("unchecked")
    var properties = (Map<String, Object>) schema.get("properties");
    @SuppressWarnings("unchecked")
    var arraySchema = (Map<String, Object>) properties.get("items");
    @SuppressWarnings("unchecked")
    var itemsSchema = (Map<String, Object>) arraySchema.get("items");

    assertEquals("string", itemsSchema.get("type"));
    assertEquals("Item description", itemsSchema.get("description"));
  }

  @Test
  void noRequiredParameters() {
    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("Test")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("optional")
                    .withType(ParameterType.STRING)
                    .withRequired(false)
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();

    var schema = tool.parametersAsJsonSchema();

    assertFalse(schema.containsKey("required"));
  }

  @Test
  void withParametersList() {
    var param1 = ToolParameter.newBuilder().withName("a").withType(ParameterType.STRING).build();
    var param2 = ToolParameter.newBuilder().withName("b").withType(ParameterType.INTEGER).build();

    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("Test")
            .withParameters(List.of(param1, param2))
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();

    assertEquals(2, tool.parameters().size());
    assertEquals("a", tool.parameters().get(0).name());
    assertEquals("b", tool.parameters().get(1).name());
  }

  @Test
  void parametersAsJsonSchemaIsImmutable() {
    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("Test")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("value")
                    .withType(ParameterType.STRING)
                    .withRequired(true)
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();

    var schema = tool.parametersAsJsonSchema();

    assertThrows(UnsupportedOperationException.class, () -> schema.put("extra", "value"));
  }

  @Test
  void nonArrayWithItemsIgnored() {
    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("Test")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("value")
                    .withType(ParameterType.STRING)
                    .withItems(ToolParameter.newBuilder().withType(ParameterType.INTEGER).build())
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();

    var schema = tool.parametersAsJsonSchema();

    @SuppressWarnings("unchecked")
    var properties = (Map<String, Object>) schema.get("properties");
    @SuppressWarnings("unchecked")
    var valueSchema = (Map<String, Object>) properties.get("value");

    assertEquals("string", valueSchema.get("type"));
    assertFalse(valueSchema.containsKey("items"));
  }

  @Test
  void defaultValueIncludedInSchema() {
    var tool =
        Tool.newBuilder()
            .withName("search")
            .withDescription("Search")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("limit")
                    .withType(ParameterType.INTEGER)
                    .withDefaultValue(10)
                    .build())
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("query")
                    .withType(ParameterType.STRING)
                    .withRequired(true)
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();

    var schema = tool.parametersAsJsonSchema();

    @SuppressWarnings("unchecked")
    var properties = (Map<String, Object>) schema.get("properties");

    @SuppressWarnings("unchecked")
    var limitSchema = (Map<String, Object>) properties.get("limit");
    assertEquals(10, limitSchema.get("default"));

    @SuppressWarnings("unchecked")
    var querySchema = (Map<String, Object>) properties.get("query");
    assertFalse(querySchema.containsKey("default"));
  }

  @Test
  void interruptedCauseRestoresInterruptStatus() {
    var tool =
        Tool.newBuilder()
            .withName("blocking")
            .withDescription("tool that wraps a blocking-call InterruptedException")
            .withExecutor(
                (args, ctx) -> {
                  throw new RuntimeException(new InterruptedException("inner blocking call"));
                })
            .build();
    assertFalse(Thread.currentThread().isInterrupted(), "test precondition");
    try {
      var result = tool.execute(Map.of());
      assertFalse(result.success());
      assertTrue(
          Thread.currentThread().isInterrupted(),
          "InterruptedException-as-cause in the executor must leave the calling thread's interrupt"
              + " status set so the session loop can promptly cancel — otherwise the interrupt is"
              + " silently swallowed by Tool.execute's catch (Exception)");
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void nonInterruptedFailureLeavesInterruptStatusAlone() {
    var tool =
        Tool.newBuilder()
            .withName("plain-fail")
            .withDescription("ordinary failure")
            .withExecutor(
                (args, ctx) -> {
                  throw new IllegalStateException("plain failure");
                })
            .build();
    assertFalse(Thread.currentThread().isInterrupted(), "test precondition");
    var result = tool.execute(Map.of());
    assertFalse(result.success());
    assertFalse(
        Thread.currentThread().isInterrupted(),
        "non-Interrupted failures must not spuriously set the calling thread's interrupt flag");
  }

  @Test
  void idempotentDefaultsToFalse() {
    var tool =
        Tool.newBuilder()
            .withName("send_email")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    assertFalse(tool.idempotent());
  }

  @Test
  void idempotentSetTrue() {
    var tool =
        Tool.newBuilder()
            .withName("get_weather")
            .withIdempotent(true)
            .withExecutor((args, ctx) -> ToolResult.success("sunny"))
            .build();
    assertTrue(tool.idempotent());
  }

  @Test
  void idempotentSetFalseExplicitly() {
    var tool =
        Tool.newBuilder()
            .withName("transfer_funds")
            .withIdempotent(false)
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    assertFalse(tool.idempotent());
  }
}
