/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
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
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.test.EventCollector;
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
 * and whether it outlived its budget, with every secret of the session's registry redacted. Every
 * limit but the budget a test exercises is beyond the hang guard; a snippet meant to time out
 * blocks on a latch nothing releases.
 */
class JShellExecuteInSessionTest {

  private static final Duration BEYOND_HANG_GUARD = Await.HANG_GUARD.multipliedBy(5);
  private static final String SECRET = "sk-test-0123456789abcdef";

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

    var toolResult = toolResults.getFirst();
    assertEquals("[runtime JSHELL exit 0 duration Nms]", header(toolResult), toolResult.output());
    assertEquals("AAPL 187.5\n", body(toolResult));
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
    assertEquals(
        "[runtime JSHELL exit 1 TIMEOUT duration Nms]", header(toolResult), toolResult.output());
    assertTrue(body(toolResult).contains("Execution timed out"), toolResult.output());
  }

  @Test
  void snippetThatCompletesOrThrowsIsNotReportedAsTimedOut() {
    var toolResults =
        executeInSession(
            replConfig(),
            Map.of("script", "println(1 + 1);"),
            Map.of("script", "throw new IllegalStateException(\"boom\");"));

    var completed = toolResults.get(0);
    var thrown = toolResults.get(1);
    assertEquals("[runtime JSHELL exit 0 duration Nms]", header(completed), completed.output());
    assertEquals("[runtime JSHELL exit 1 duration Nms]", header(thrown), thrown.output());
    assertTrue(body(thrown).contains("boom"), thrown.output());
    toolResults.forEach(toolResult -> assertFalse(toolResult.output().contains("TIMEOUT")));
  }

  @Test
  void secretASnippetPrintsReachesTheToolResultRedacted() {
    var secrets = new SecretRegistry();
    secrets.register("API_KEY", SECRET);

    var event =
        toolResultEvents(
                replConfig(),
                secrets,
                Map.of("script", "System.out.println(\"sk-test-\" + \"0123456789abcdef\");"))
            .getFirst();

    var output = event.result().output();
    assertTrue(output.contains("<redacted:API_KEY>"), output);
    assertFalse(output.contains(SECRET), output);
    assertEquals(Map.of("API_KEY", 1), event.redactions());
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

  @SafeVarargs
  private static List<ToolResult> executeInSession(
      ReplConfig.Builder replConfig, Map<String, Object>... calls) {
    return toolResultEvents(replConfig, new SecretRegistry(), calls).stream()
        .map(QueryEvent.ToolResult::result)
        .toList();
  }

  /**
   * Runs one session holding {@code secrets} whose model calls {@code Execute} with each of {@code
   * calls} in turn, the runtime set to {@code JSHELL}, and returns the tool result events in call
   * order.
   */
  @SafeVarargs
  private static List<QueryEvent.ToolResult> toolResultEvents(
      ReplConfig.Builder replConfig, SecretRegistry secrets, Map<String, Object>... calls) {
    var script = ScriptedModel.newBuilder();
    for (var i = 0; i < calls.length; i++) {
      var args = new HashMap<String, Object>(calls[i]);
      args.put("runtime", "JSHELL");
      script.withToolCallsTurn(Usage.of(1, 1), new ToolCall("c" + i, ExecuteTool.NAME, args));
    }
    var model = script.withTextTurn("done", Usage.of(1, 1)).build();
    var events = new EventCollector();

    try (var provider =
        JShellExecutionProvider.newBuilder()
            .withReplConfig(replConfig.build())
            .withShutdownHook(false)
            .build()) {
      var options =
          SessionOptions.newBuilder()
              .withModel(model)
              .withExecutionProvider(provider)
              .withPermission(Permission.lockedDown())
              .withSecretRegistry(secrets)
              .withLimits(
                  SessionLimits.newBuilder().withToolTimeoutDefault(BEYOND_HANG_GUARD).build())
              .build();
      try (var session = AgentSession.create(options)) {
        events.read(session.events());
        assertInstanceOf(
            ResultMessage.Success.class, session.runBlocking(UserMessage.text("run the snippets")));
        events.awaitDone();
      }
    }

    var toolResults = events.eventsOf(QueryEvent.ToolResult.class);
    assertEquals(calls.length, toolResults.size());
    return toolResults;
  }

  /** The output's header line, its duration replaced by {@code N}. */
  private static String header(ToolResult toolResult) {
    return toolResult
        .output()
        .lines()
        .findFirst()
        .orElseThrow()
        .replaceFirst(" duration \\d+ms]$", " duration Nms]");
  }

  /** The output after its header line. */
  private static String body(ToolResult toolResult) {
    var output = toolResult.output();
    return output.substring(output.indexOf('\n') + 1);
  }
}
