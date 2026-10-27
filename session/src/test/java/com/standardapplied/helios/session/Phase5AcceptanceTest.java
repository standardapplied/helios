/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.process.BinaryResolver;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.session.execution.ExecuteTool;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.execution.ExecutionRequest;
import com.standardapplied.helios.session.execution.ExecutionResult;
import com.standardapplied.helios.session.execution.LocalProcessExecutionProvider;
import com.standardapplied.helios.session.execution.Runtime;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.testing.ScriptedModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 5 acceptance per spec §20: the agent runs a Python script and gets stdout back; a deny rule
 * blocks a Bash call to a forbidden binary; a slow Bash sleep against a tight timeout surfaces
 * {@code timedOut: true} in the structured tool-result data.
 */
final class Phase5AcceptanceTest {

  /**
   * A test that fails before its children are reaped must not leave one running; nothing else in
   * this JVM starts a process while a test of this class runs.
   */
  @AfterEach
  void noChildOutlivesItsTest() {
    ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
  }

  private record Run(ResultMessage result, EventCollector events) {}

  /** A model that calls Execute once with {@code arguments}, then answers "done". */
  private static ScriptedModel executing(Map<String, Object> arguments) {
    return ScriptedModel.newBuilder()
        .withToolCallsTurn(Usage.of(1, 1), new ToolCall("c1", ExecuteTool.NAME, arguments))
        .withTextTurn("done", Usage.of(1, 1))
        .build();
  }

  /**
   * Allows Execute — DEFAULT mode otherwise routes to ASK and blocks — and applies {@code deny}.
   */
  private static Permission allowingExecute(PermissionRule... deny) {
    return new Permission(
        PermissionMode.DEFAULT,
        List.of(PermissionRule.any(ExecuteTool.NAME)),
        List.of(),
        List.of(deny));
  }

  /** Runs {@code prompt} through a session with Execute on a local process provider. */
  private static Run run(
      String sessionPrefix, ScriptedModel model, Permission permission, String prompt) {
    return run(
        options(sessionPrefix, model, permission, LocalProcessExecutionProvider.defaultPosix()),
        prompt);
  }

  private static SessionOptions.Builder options(
      String sessionPrefix,
      ScriptedModel model,
      Permission permission,
      ExecutionProvider provider) {
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId(sessionPrefix + UUID.randomUUID())
        .withExecutionProvider(provider)
        .withPermission(permission);
  }

  private static Run run(SessionOptions.Builder options, String prompt) {
    var sub = new EventCollector();
    try (var session = AgentSession.create(options.build())) {
      sub.read(session.events());
      var result = session.runBlocking(UserMessage.text(prompt));
      sub.awaitDone();
      return new Run(result, sub);
    }
  }

  @Test
  void agentRunsPythonScriptAndReceivesStdout() throws Exception {
    assumePythonAvailable();
    var model = executing(Map.of("runtime", "PYTHON", "script", "print('phase-5')"));

    var run = run("phase5-py-", model, allowingExecute(), "run a python script");
    assertInstanceOf(ResultMessage.Success.class, run.result());

    var toolResults = run.events().eventsOf(QueryEvent.ToolResult.class);
    assertEquals(1, toolResults.size());
    var output = toolResults.get(0).result().output();
    assertTrue(output.startsWith("[runtime PYTHON exit 0 "), output);
    assertTrue(output.endsWith("]\nphase-5\n"), output);
  }

  @Test
  void denyRuleBlocksBashCallToForbiddenBinary() throws Exception {
    assumeBashAvailable();
    var model = executing(Map.of("runtime", "BASH", "script", "rm -rf /tmp/should-not-happen"));
    var permission = allowingExecute(PermissionRule.of(ExecuteTool.NAME, "BASH/**rm **"));

    var run = run("phase5-deny-", model, permission, "try a forbidden command");

    var blocked =
        run.events().eventsOf(QueryEvent.ToolBlocked.class).stream().findFirst().orElse(null);
    assertNotNull(blocked, "expected Execute(BASH/**rm **) to be blocked by deny rule");
    assertEquals(ExecuteTool.NAME, blocked.call().name());
  }

  @Test
  void slowBashCallAgainstTightTimeoutSurfacesTimedOutTrue() throws Exception {
    assumeBashAvailable();
    // The script cannot finish on its own before the hang guard, so only the one-second execution
    // timeout can end it; ExecuteTool reports TIMEOUT in the tool result's text.
    var model =
        executing(Map.of("runtime", "BASH", "script", "exec sleep 600", "timeoutSeconds", 1));

    var run = run("phase5-timeout-", model, allowingExecute(), "trigger timeout");
    assertInstanceOf(ResultMessage.Success.class, run.result());

    var toolResult =
        run.events().eventsOf(QueryEvent.ToolResult.class).stream().findFirst().orElseThrow();
    var output = toolResult.result().output();
    assertTrue(output.startsWith("[runtime BASH exit -1 TIMEOUT "), output);
  }

  /**
   * The tool call's own timeout interrupts its thread, which runs the provider's {@code execute}:
   * the child is killed and the call ends {@code TimedOut}. The script cannot end on its own, and
   * its requested budget and the stop grace lie beyond the hang guard, so only the tool timeout can
   * end it.
   */
  @Test
  void toolTimeoutInterruptsTheProvidersCallAndKillsTheChild() {
    assumeBashAvailable();
    var limit = Duration.ofMillis(200);
    var model =
        executing(Map.of("runtime", "BASH", "script", "exec sleep 600", "timeoutSeconds", 3600));
    try (var provider =
        LocalProcessExecutionProvider.newBuilder()
            .withRuntime(Runtime.BASH, LocalProcessExecutionProvider.RuntimeHandler.dashC("bash"))
            .withMaxTimeout(Duration.ofHours(1))
            .withShutdownHook(false)
            .build()) {
      var options =
          options("phase5-tool-timeout-", model, allowingExecute(), provider)
              .withLimits(
                  SessionLimits.newBuilder()
                      .withToolTimeoutDefault(limit)
                      .withStopGrace(Duration.ofMinutes(10))
                      .build());

      var run = run(options, "trigger the tool timeout");

      var toolResult =
          run.events().eventsOf(QueryEvent.ToolResult.class).stream().findFirst().orElseThrow();
      assertEquals(new ToolOutcome.TimedOut(limit), toolResult.result().outcome());
      assertEquals(0, provider.inflightCount());
    }
  }

  /** An {@code Error} the provider throws ends the session as itself, through the tool worker. */
  @Test
  void anErrorTheProviderThrowsFailsTheResultAsItself() {
    var failure = new AssertionError("provider failure");
    var provider =
        new ExecutionProvider() {
          @Override
          public Set<Runtime> supportedRuntimes() {
            return Set.of(Runtime.BASH);
          }

          @Override
          public ExecutionResult execute(
              SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
            throw failure;
          }
        };
    var model = executing(Map.of("runtime", "BASH", "script", "true"));

    try (var session =
        AgentSession.create(options("phase5-error-", model, allowingExecute(), provider).build())) {
      session.send("run it");

      assertSame(failure, Await.failure("the result to fail", session.result()));
    }
  }

  private static void assumeBashAvailable() {
    assumeTrue(
        Files.isExecutable(Path.of("/bin/bash")) || Files.isExecutable(Path.of("/usr/bin/bash")),
        "bash is not available; skipping Phase 5 BASH scenarios");
  }

  private static void assumePythonAvailable() {
    try {
      BinaryResolver.resolve("python3", System.getenv("PATH"));
    } catch (RuntimeException e) {
      assumeTrue(false, "python3 is not available; skipping Phase 5 PYTHON scenarios");
    }
  }
}
