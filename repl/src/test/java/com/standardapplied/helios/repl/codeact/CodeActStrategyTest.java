/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.host.HostParameter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for {@link CodeActStrategy}. The end-to-end behaviour is covered by {@code
 * Phase6AcceptanceTest}; this class pins down the prompt-builder contract — null checks, the
 * bound/unbound branch, and that strategy text + custom host functions appear in the rendered
 * prompt.
 */
final class CodeActStrategyTest {

  public record Input(String topic, int count) {}

  public record Answer(String value) {}

  private static OutputSchema<?> inputSchema() {
    return OutputSchema.of(Input.class);
  }

  private static OutputSchema<?> outputSchema() {
    return OutputSchema.of(Answer.class);
  }

  @Test
  void buildSystemPromptRejectsNullInputSchema() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                CodeActStrategy.buildSystemPrompt(
                    null, outputSchema(), 5000, List.of(), List.of(), null));
    assertEquals("inputSchema must not be null", ex.getMessage());
  }

  @Test
  void buildSystemPromptRejectsNullOutputSchema() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                CodeActStrategy.buildSystemPrompt(
                    inputSchema(), null, 5000, List.of(), List.of(), null));
    assertEquals("outputSchema must not be null", ex.getMessage());
  }

  @Test
  void boundFieldsTellTheModelVariablesAreReady() {
    var prompt =
        CodeActStrategy.buildSystemPrompt(
            inputSchema(), outputSchema(), 5000, List.of("topic", "count"), List.of(), null);
    assertTrue(prompt.contains("already bound as JShell variables"));
    assertTrue(prompt.contains("topic"));
    assertTrue(prompt.contains("count"));
    assertFalse(prompt.contains("not pre-bound"));
  }

  @Test
  void unboundFieldsTellTheModelToReadJsonUserMessage() {
    var prompt =
        CodeActStrategy.buildSystemPrompt(
            inputSchema(), outputSchema(), 5000, List.of(), List.of(), null);
    assertTrue(prompt.contains("not pre-bound"));
    assertTrue(prompt.contains("read each one as a literal"));
    assertFalse(prompt.contains("already bound as JShell variables"));
  }

  @Test
  void nullBoundFieldNamesBehavesLikeEmpty() {
    var prompt =
        CodeActStrategy.buildSystemPrompt(
            inputSchema(), outputSchema(), 5000, null, List.of(), null);
    assertTrue(prompt.contains("not pre-bound"));
  }

  @Test
  void strategyTextRendersUnderTaskStrategyHeader() {
    var prompt =
        CodeActStrategy.buildSystemPrompt(
            inputSchema(),
            outputSchema(),
            5000,
            List.of(),
            List.of(),
            "  Read every value, validate types, then sum.  ");
    assertTrue(prompt.contains("## Task strategy"));
    assertTrue(prompt.contains("Read every value, validate types, then sum."));
  }

  @Test
  void blankStrategyTextSuppressesHeader() {
    var prompt =
        CodeActStrategy.buildSystemPrompt(
            inputSchema(), outputSchema(), 5000, List.of(), List.of(), "   ");
    assertFalse(prompt.contains("## Task strategy"));
  }

  @Test
  void truncationCapAppearsInPrompt() {
    var prompt =
        CodeActStrategy.buildSystemPrompt(
            inputSchema(), outputSchema(), 1234, List.of(), List.of(), null);
    assertTrue(prompt.contains("1234"));
  }

  @Test
  void customHostFunctionsRenderUnderCustomBlock() {
    var fn =
        new HostFunction(
            "marketQuote",
            "Looks up a market price",
            List.of(HostParameter.required("ticker", ParameterType.STRING, "Ticker symbol")),
            params -> Map.of("output", "x"));
    var prompt =
        CodeActStrategy.buildSystemPrompt(
            inputSchema(), outputSchema(), 5000, List.of(), List.of(fn), null);
    assertTrue(prompt.contains("Custom host functions registered for this run:"));
    assertTrue(prompt.contains("marketQuote"));
    assertTrue(prompt.contains("Looks up a market price"));
    assertTrue(prompt.contains("ticker"));
  }

  @Test
  void promptAlwaysReferencesExecuteToolAndJSHELL() {
    var prompt =
        CodeActStrategy.buildSystemPrompt(
            inputSchema(), outputSchema(), 5000, List.of(), List.of(), null);
    assertTrue(prompt.contains("Execute("));
    assertTrue(prompt.contains("JSHELL"));
    assertTrue(prompt.contains("Required output schema"));
  }
}
