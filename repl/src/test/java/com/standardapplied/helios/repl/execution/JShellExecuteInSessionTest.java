/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.repl.ReplConfig;
import com.standardapplied.helios.repl.host.HostFunction;
import com.standardapplied.helios.repl.host.HostParameter;
import com.standardapplied.helios.repl.sandbox.JvmSandbox;
import com.standardapplied.helios.repl.sandbox.JvmSandboxConfig;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.execution.ExecuteTool;
import com.standardapplied.helios.session.execution.ExecutionResult;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * A session's {@code Execute(JSHELL)} call runs in a real sandbox subprocess through {@link
 * JShellExecutionProvider}: the snippet calls a host function registered through {@link
 * ReplConfig.Builder#withHostFunction}, and the tool result carries the snippet's stdout and exit
 * code.
 */
class JShellExecuteInSessionTest {

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
    var sandboxes =
        JvmSandbox.factory(
            JvmSandboxConfig.newBuilder()
                .withSubprocessStartupTimeout(Await.HANG_GUARD)
                .withCallTimeout(Await.HANG_GUARD)
                .build());
    var replConfig =
        ReplConfig.newBuilder().withSandboxFactory(sandboxes).withHostFunction(quote).build();
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(
                Usage.of(1, 1),
                new ToolCall(
                    "c1",
                    ExecuteTool.NAME,
                    Map.of(
                        "runtime",
                        "JSHELL",
                        "script",
                        "println(\"AAPL \" + marketQuote(\"AAPL\"));")))
            .withTextTurn("done", Usage.of(1, 1))
            .build();
    var events = new CollectingSubscriber();

    try (var provider =
        JShellExecutionProvider.newBuilder()
            .withReplConfig(replConfig)
            .withShutdownHook(false)
            .build()) {
      var options =
          SessionOptions.newBuilder()
              .withModel(model)
              .withTools(new ToolRegistry(List.of(ExecuteTool.binding(provider))))
              .withExecutionProvider(provider)
              .withPermission(Permission.lockedDown())
              .build();
      try (var session = AgentSession.create(options)) {
        session.events().subscribe(events);
        assertInstanceOf(
            ResultMessage.Success.class, session.runBlocking(UserMessage.text("quote AAPL")));
        events.awaitDone();
      }
    }

    var toolResults = events.eventsOf(QueryEvent.ToolResult.class);
    assertEquals(1, toolResults.size());
    var result = assertInstanceOf(ExecutionResult.class, toolResults.get(0).result().data());
    assertEquals(0, result.exitCode(), result.stderr());
    assertEquals("AAPL 187.5\n", result.stdout());
    assertEquals(List.of("AAPL"), tickers);
  }
}
