/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostCalculator.Pricing;
import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Every turn's usage reaches the session's totals before a hook or a terminal decision reads them:
 * a tool hook's {@code Stop}, a schema self-correction turn and a compactor that reports only cache
 * tokens.
 */
class TurnUsageAccountingTest {

  public record Sample(String field) {}

  private static final String MAIN = "main-model";
  private static final String SUMMARIZER = "summarizer-model";
  private static final CostCalculator RATES =
      CostCalculator.staticTable(
          Map.of(
              MAIN, new Pricing(1_000_000L, 2_000_000L),
              SUMMARIZER, new Pricing(0L, 0L, 1_000_000L, 500_000L)));

  @Test
  void aPreToolUseStopCarriesTheTurnThatCalledTheTool() {
    var success =
        runToSuccess(
            toolSession(
                (PreToolUseHook) (call, ctx) -> HookOutcome.stop("stopped before the tool")));

    assertEquals(Usage.of(100, 20), success.usage());
    assertEquals(new CostEstimate(140L), success.cost());
  }

  @Test
  void aPostToolUseStopCarriesTheTurnThatCalledTheTool() {
    var success =
        runToSuccess(
            toolSession(
                (PostToolUseHook)
                    (call, result, ctx) -> HookOutcome.stop("stopped after the tool")));

    assertEquals(Usage.of(100, 20), success.usage());
    assertEquals(new CostEstimate(140L), success.cost());
  }

  @Test
  void aSchemaSelfCorrectionTurnIsAccounted() {
    var model =
        ScriptedModel.newBuilder()
            .withId(MAIN)
            .withStreamTurn(
                ModelStreams.failing(
                    new StructuredOutputParseException(
                        List.of("field is required but missing"), "{\"wrong\":\"shape\"}"),
                    new ModelChunk.MessageStop("STOP", Usage.of(50, 10), Map.of())))
            .withTextTurn("{\"field\":\"recovered\"}", Usage.of(70, 5))
            .build();

    var success =
        runToSuccess(
            SessionOptions.newBuilder()
                .withModel(model)
                .withCostCalculator(RATES)
                .withOutputSchema(OutputSchema.of(Sample.class)));

    assertEquals(Usage.of(120, 15), success.usage());
    assertEquals(new CostEstimate(150L), success.cost());
  }

  @ParameterizedTest
  @CsvSource({"0, 900, 450", "900, 0, 900"})
  void aCompactionReportingOnlyCacheTokensIsAccountedAtTheCompactorsRate(
      int cacheWrites, int cacheReads, long expectedCost) {
    var usage = Usage.of(0, 0, cacheWrites, cacheReads);
    var model =
        ScriptedModel.newBuilder()
            .withId(MAIN)
            .withToolCallsTurn(new ToolCall("c1", "echo", Map.of()))
            .withTextTurn("done")
            .build();
    ContextCompactor cacheOnly =
        (history, state) ->
            new CompactionResult(List.of(Message.user("the gist")), usage, SUMMARIZER);

    var success =
        runToSuccess(
            SessionOptions.newBuilder()
                .withModel(model)
                .withCostCalculator(RATES)
                .withTools(List.of(echo()))
                .withLimits(SessionLimits.newBuilder().withMaxContextTokens(100L).build())
                .withTokenCounter(
                    messages -> messages.stream().anyMatch(m -> m.role() == Role.TOOL) ? 96L : 10L)
                .withContextCompactor(cacheOnly));

    assertEquals(usage, success.usage());
    assertEquals(new CostEstimate(expectedCost), success.cost());
  }

  /** A session whose one model turn reports 100 input and 20 output tokens and calls a tool. */
  private static SessionOptions.Builder toolSession(Hook stoppingHook) {
    var model =
        ScriptedModel.newBuilder()
            .withId(MAIN)
            .withToolCallsTurn(Usage.of(100, 20), new ToolCall("c1", "echo", Map.of()))
            .build();
    return SessionOptions.newBuilder()
        .withModel(model)
        .withCostCalculator(RATES)
        .withTools(List.of(echo()))
        .withHook(stoppingHook);
  }

  private static ResultMessage.Success runToSuccess(SessionOptions.Builder options) {
    try (var session = AgentSession.open(options.build())) {
      return assertInstanceOf(
          ResultMessage.Success.class, session.runBlocking(UserMessage.text("go")));
    }
  }

  private static ToolBinding echo() {
    var tool =
        Tool.newBuilder()
            .withName("echo")
            .withDescription("answers ok")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    return ToolBinding.newBuilder(tool).withCategory(ToolCategory.READ).build();
  }
}
