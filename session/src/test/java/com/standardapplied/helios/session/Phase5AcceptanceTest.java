/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.process.BinaryResolver;
import com.standardapplied.helios.session.execution.ExecuteTool;
import com.standardapplied.helios.session.execution.ExecutionResult;
import com.standardapplied.helios.session.execution.LocalProcessExecutionProvider;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

  private record Run(ResultMessage result, CollectingSubscriber events) {}

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
        List.of(PermissionRule.any(PermissionEffect.ALLOW, ExecuteTool.NAME)),
        List.of(),
        List.of(deny));
  }

  /** Runs {@code prompt} through a session with Execute on a local process provider. */
  private static Run run(
      String sessionPrefix, ScriptedModel model, Permission permission, String prompt) {
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSessionId(sessionPrefix + UUID.randomUUID())
            .withTools(new ToolRegistry(List.of(ExecuteTool.binding(provider))))
            .withExecutionProvider(provider)
            .withPermission(permission)
            .build();
    var sub = new CollectingSubscriber();
    try (var session = AgentSession.create(options)) {
      session.events().subscribe(sub);
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
    var execResult = assertInstanceOf(ExecutionResult.class, toolResults.get(0).result().data());
    assertEquals(0, execResult.exitCode());
    assertEquals("phase-5\n", execResult.stdout());
    assertTrue(toolResults.get(0).result().output().contains("phase-5"));
  }

  @Test
  void denyRuleBlocksBashCallToForbiddenBinary() throws Exception {
    assumeBashAvailable();
    var model = executing(Map.of("runtime", "BASH", "script", "rm -rf /tmp/should-not-happen"));
    var permission =
        allowingExecute(
            PermissionRule.withGlob(PermissionEffect.DENY, ExecuteTool.NAME, "BASH/rm"));

    var run = run("phase5-deny-", model, permission, "try a forbidden command");

    var blocked =
        run.events().eventsOf(QueryEvent.ToolBlocked.class).stream().findFirst().orElse(null);
    assertNotNull(blocked, "expected Execute(BASH/rm) to be blocked by deny rule");
    assertEquals(ExecuteTool.NAME, blocked.call().name());
  }

  @Test
  void slowBashCallAgainstTightTimeoutSurfacesTimedOutTrue() throws Exception {
    assumeBashAvailable();
    // The script cannot finish on its own before the hang guard, so only the one-second execution
    // timeout can end it; ExecuteTool surfaces timedOut=true in the structured tool result.
    var model =
        executing(Map.of("runtime", "BASH", "script", "exec sleep 600", "timeoutSeconds", 1));

    var run = run("phase5-timeout-", model, allowingExecute(), "trigger timeout");
    assertInstanceOf(ResultMessage.Success.class, run.result());

    var toolResult =
        run.events().eventsOf(QueryEvent.ToolResult.class).stream().findFirst().orElseThrow();
    var execResult = assertInstanceOf(ExecutionResult.class, toolResult.result().data());
    assertTrue(execResult.timedOut(), "expected timedOut=true on the structured result");
    assertEquals(-1, execResult.exitCode());
    assertTrue(toolResult.result().output().contains("TIMEOUT"));
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
