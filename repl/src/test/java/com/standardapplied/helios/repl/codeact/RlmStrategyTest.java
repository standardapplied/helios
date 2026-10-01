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
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for {@link RlmStrategy}. Pins down the prompt builder's contract: null argument
 * handling, the bound/unbound branch, the optional budget paragraph, and the load-bearing "submit
 * alone" / "submit ends the run" language that the v1 RLM port took years to crystallize.
 */
final class RlmStrategyTest {

  public record Input(String topic) {}

  public record Answer(String headline) {}

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
                RlmStrategy.buildSystemPrompt(
                    null, outputSchema(), 5000, OptionalInt.empty(), List.of(), List.of(), null));
    assertEquals("inputSchema must not be null", ex.getMessage());
  }

  @Test
  void buildSystemPromptRejectsNullOutputSchema() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                RlmStrategy.buildSystemPrompt(
                    inputSchema(), null, 5000, OptionalInt.empty(), List.of(), List.of(), null));
    assertEquals("outputSchema must not be null", ex.getMessage());
  }

  @Test
  void buildSystemPromptRejectsNullMaxLlmCalls() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                RlmStrategy.buildSystemPrompt(
                    inputSchema(), outputSchema(), 5000, null, List.of(), List.of(), null));
    assertEquals("maxLlmCalls must not be null", ex.getMessage());
  }

  @Test
  void buildSystemPromptRejectsZeroBudget() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RlmStrategy.buildSystemPrompt(
                    inputSchema(),
                    outputSchema(),
                    5000,
                    OptionalInt.of(0),
                    List.of(),
                    List.of(),
                    null));
    assertTrue(ex.getMessage().contains("strictly positive"));
  }

  @Test
  void buildSystemPromptRejectsNegativeBudget() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RlmStrategy.buildSystemPrompt(
                inputSchema(),
                outputSchema(),
                5000,
                OptionalInt.of(-1),
                List.of(),
                List.of(),
                null));
  }

  @Test
  void emptyMaxLlmCallsOmitsBudgetParagraph() {
    var prompt =
        RlmStrategy.buildSystemPrompt(
            inputSchema(), outputSchema(), 5000, OptionalInt.empty(), List.of(), List.of(), null);
    assertFalse(prompt.contains("Budget: you have at most"));
  }

  @Test
  void presentMaxLlmCallsRendersBudgetParagraphWithCount() {
    var prompt =
        RlmStrategy.buildSystemPrompt(
            inputSchema(), outputSchema(), 5000, OptionalInt.of(7), List.of(), List.of(), null);
    assertTrue(prompt.contains("Budget: you have at most 7 predict() calls"));
    assertTrue(prompt.contains("SandboxBudgetExceededException"));
  }

  @Test
  void promptAlwaysExplainsSubmitOwnership() {
    var prompt =
        RlmStrategy.buildSystemPrompt(
            inputSchema(), outputSchema(), 5000, OptionalInt.empty(), List.of(), List.of(), null);
    assertTrue(prompt.contains("submit("));
    assertTrue(prompt.contains("This is NOT a notebook"));
    assertTrue(prompt.contains("submit ALONE in the next call"));
  }

  @Test
  void boundFieldsTellTheModelVariablesAreReady() {
    var prompt =
        RlmStrategy.buildSystemPrompt(
            inputSchema(),
            outputSchema(),
            5000,
            OptionalInt.empty(),
            List.of("topic"),
            List.of(),
            null);
    assertTrue(prompt.contains("already bound as JShell variables"));
    assertTrue(prompt.contains("topic"));
  }

  @Test
  void unboundFieldsTellTheModelToReadJsonUserMessage() {
    var prompt =
        RlmStrategy.buildSystemPrompt(
            inputSchema(), outputSchema(), 5000, OptionalInt.empty(), List.of(), List.of(), null);
    assertTrue(prompt.contains("not pre-bound"));
  }

  @Test
  void strategyTextRendersUnderHeader() {
    var prompt =
        RlmStrategy.buildSystemPrompt(
            inputSchema(),
            outputSchema(),
            5000,
            OptionalInt.empty(),
            List.of(),
            List.of(),
            "Be deliberate, verify first.");
    assertTrue(prompt.contains("## Task strategy"));
    assertTrue(prompt.contains("Be deliberate, verify first."));
  }

  @Test
  void blankStrategyTextSuppressesHeader() {
    var prompt =
        RlmStrategy.buildSystemPrompt(
            inputSchema(), outputSchema(), 5000, OptionalInt.empty(), List.of(), List.of(), "   ");
    assertFalse(prompt.contains("## Task strategy"));
  }

  @Test
  void truncationCapAppearsInPrompt() {
    var prompt =
        RlmStrategy.buildSystemPrompt(
            inputSchema(), outputSchema(), 987, OptionalInt.empty(), List.of(), List.of(), null);
    assertTrue(prompt.contains("987"));
  }
}
