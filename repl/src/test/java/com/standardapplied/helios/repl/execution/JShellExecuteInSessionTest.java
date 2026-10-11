/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.repl.ReplConfig;
import com.standardapplied.helios.repl.host.HostFunction;
import com.standardapplied.helios.repl.host.HostParameter;
import com.standardapplied.helios.repl.sandbox.JvmSandbox;
import com.standardapplied.helios.repl.sandbox.JvmSandboxConfig;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.execution.ExecuteTool;
import com.standardapplied.helios.session.execution.ExecutionResult;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * A session's {@code Execute(JSHELL)} call runs in a real sandbox subprocess through {@link
 * JShellExecutionProvider}: the snippet calls a host function registered through {@link
 * ReplConfig.Builder#withHostFunction}, and the tool result carries the snippet's stdout, exit code
 * and whether it outlived its budget. Every limit but the budget a test exercises is beyond the
 * hang guard; a snippet meant to time out blocks on a latch nothing releases.
 */
class JShellExecuteInSessionTest {

  private static final Duration BEYOND_HANG_GUARD = Await.HANG_GUARD.multipliedBy(5);

  @Test
  void executeRunsASnippetThatCallsACustomHostFunction() {
    var tickers = new CopyOnWriteArrayList<Object>();
    var quote =
        new HostFunction(
            "marketQuote",
            "Current price of a ticker",
            List.of(HostParameter.required("ticker", ParameterType.STRING, "Ticker symbol")),
            params -> {
              tickers.add(params.get("ticker"));
              return 187.5;
            });

    var toolResults =
        executeInSession(
            replConfig().withHostFunction(quote),
            Map.of("script", "println(\"AAPL \" + marketQuote(\"AAPL\"));"));

    var result = executionResult(toolResults.getFirst());
    assertEquals(0, result.exitCode(), result.stderr());
    assertEquals("AAPL 187.5\n", result.stdout());
    assertEquals(List.of("AAPL"), tickers);
  }

  @Test
  void snippetThatOutlivesItsBudgetIsReportedToTheModelAsTimedOut() {
    var toolResults =
        executeInSession(
            replConfig(),
            Map.of(
                "script",
                "new java.util.concurrent.CountDownLatch(1).await();",
                "timeoutSeconds",
                1));

    var toolResult = toolResults.getFirst();
    var result = executionResult(toolResult);
    assertTrue(result.timedOut(), result.stderr());
    assertTrue(result.stderr().contains("Execution timed out"), result.stderr());
    assertTrue(toolResult.output().startsWith("[runtime JSHELL exit 1 TIMEOUT duration "));
  }

  @Test
  void snippetThatCompletesOrThrowsIsNotReportedAsTimedOut() {
    var toolResults =
        executeInSession(
            replConfig(),
            Map.of("script", "println(1 + 1);"),
            Map.of("script", "throw new IllegalStateException(\"boom\");"));

    var completed = executionResult(toolResults.get(0));
    var thrown = executionResult(toolResults.get(1));
    assertEquals(0, completed.exitCode(), completed.stderr());
    assertFalse(completed.timedOut());
    assertEquals(1, thrown.exitCode());
    assertTrue(thrown.stderr().contains("boom"), thrown.stderr());
    assertFalse(thrown.timedOut());
    toolResults.forEach(toolResult -> assertFalse(toolResult.output().contains("TIMEOUT")));
  }

  private static ReplConfig.Builder replConfig() {
    var sandboxes =
        JvmSandbox.factory(
            JvmSandboxConfig.newBuilder()
                .withSubprocessStartupTimeout(Await.HANG_GUARD)
                .withCallTimeout(Await.HANG_GUARD)
                .withStopGrace(BEYOND_HANG_GUARD)
                .build());
    return ReplConfig.newBuilder().withSandboxFactory(sandboxes);
  }

  /**
   * Runs one session whose model calls {@code Execute} with each of {@code calls} in turn, the
   * runtime set to {@code JSHELL}, and returns the tool results in call order.
   */
  @SafeVarargs
  private static List<ToolResult> executeInSession(
      ReplConfig.Builder replConfig, Map<String, Object>... calls) {
    var script = ScriptedModel.newBuilder();
    for (var i = 0; i < calls.length; i++) {
      var args = new HashMap<String, Object>(calls[i]);
      args.put("runtime", "JSHELL");
      script.withToolCallsTurn(Usage.of(1, 1), new ToolCall("c" + i, ExecuteTool.NAME, args));
    }
    var model = script.withTextTurn("done", Usage.of(1, 1)).build();
    var events = new CollectingSubscriber();

    try (var provider =
        JShellExecutionProvider.newBuilder()
            .withReplConfig(replConfig.build())
            .withShutdownHook(false)
            .build()) {
      var options =
          SessionOptions.newBuilder()
              .withModel(model)
              .withTools(new ToolRegistry(List.of(ExecuteTool.binding(provider))))
              .withExecutionProvider(provider)
              .withPermission(Permission.lockedDown())
              .withLimits(
                  SessionLimits.newBuilder().withToolTimeoutDefault(BEYOND_HANG_GUARD).build())
              .build();
      try (var session = AgentSession.create(options)) {
        session.events().subscribe(events);
        assertInstanceOf(
            ResultMessage.Success.class, session.runBlocking(UserMessage.text("run the snippets")));
        events.awaitDone();
      }
    }

    var toolResults =
        events.eventsOf(QueryEvent.ToolResult.class).stream()
            .map(QueryEvent.ToolResult::result)
            .toList();
    assertEquals(calls.length, toolResults.size());
    return toolResults;
  }

  private static ExecutionResult executionResult(ToolResult toolResult) {
    return assertInstanceOf(ExecutionResult.class, toolResult.data());
  }
}
