/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.singlr.core.common.SecretRegistry;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelChunk;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.CommandGrant;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.execution.ExecuteTool;
import ai.singlr.session.execution.ExecutionResult;
import ai.singlr.session.execution.LocalProcessExecutionProvider;
import ai.singlr.session.permissions.Permission;
import ai.singlr.session.permissions.PermissionEffect;
import ai.singlr.session.permissions.PermissionMode;
import ai.singlr.session.permissions.PermissionRule;
import ai.singlr.session.tools.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Phase 5 acceptance per spec §20: the agent runs a Python script and gets stdout back; a deny rule
 * blocks a Bash call to a forbidden binary; a slow Bash sleep against a tight timeout surfaces
 * {@code timedOut: true} in the structured tool-result data.
 */
final class Phase5AcceptanceTest {

  private static final class ScriptedModel implements Model {
    private final List<List<ModelChunk>> turns;
    private int turnIndex = 0;

    ScriptedModel(List<List<ModelChunk>> turns) {
      this.turns = turns;
    }

    @Override
    public Response<Void> chat(List<Message> messages, List<Tool> tools) {
      throw new AssertionError("unused");
    }

    @Override
    public Flow.Publisher<ModelChunk> chatStream(
        List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
      var chunks = turnIndex < turns.size() ? turns.get(turnIndex++) : List.<ModelChunk>of();
      return subscriber ->
          subscriber.onSubscribe(
              new Flow.Subscription() {
                @Override
                public void request(long n) {
                  for (var c : chunks) {
                    subscriber.onNext(c);
                  }
                  subscriber.onComplete();
                }

                @Override
                public void cancel() {}
              });
    }

    @Override
    public String id() {
      return "phase5-script";
    }

    @Override
    public String provider() {
      return "test";
    }
  }

  @Test
  void agentRunsPythonScriptAndReceivesStdout() throws Exception {
    assumePythonAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());

    var turns =
        List.<List<ModelChunk>>of(
            List.of(
                new ModelChunk.ToolUseStop(
                    new ToolCall(
                        "c1",
                        ExecuteTool.NAME,
                        Map.of("runtime", "PYTHON", "script", "print('phase-5')"))),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(1, 1))),
            List.of(
                new ModelChunk.TextDelta("done"),
                new ModelChunk.MessageStop("STOP", Usage.of(1, 1))));

    // Permission policy must allow Execute — DEFAULT mode otherwise routes to ASK and blocks.
    var permission =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(PermissionRule.any(PermissionEffect.ALLOW, ExecuteTool.NAME)),
            List.of(),
            List.of());

    var options =
        SessionOptions.newBuilder()
            .withModel(new ScriptedModel(turns))
            .withSessionId("phase5-py-" + UUID.randomUUID())
            .withTools(new ToolRegistry(List.of(ExecuteTool.binding(provider))))
            .withExecutionProvider(provider)
            .withPermission(permission)
            .build();

    var events = new CopyOnWriteArrayList<QueryEvent>();
    try (var session = AgentSession.create(options)) {
      session.events().subscribe(collectingSubscriber(events, null));
      var result = session.runBlocking(UserMessage.text("run a python script"));
      assertInstanceOf(ResultMessage.Success.class, result);
    }

    var toolResults =
        events.stream()
            .filter(e -> e instanceof QueryEvent.ToolResult)
            .map(e -> (QueryEvent.ToolResult) e)
            .toList();
    assertEquals(1, toolResults.size());
    var execResult = assertInstanceOf(ExecutionResult.class, toolResults.get(0).result().data());
    assertEquals(0, execResult.exitCode());
    assertEquals("phase-5\n", execResult.stdout());
    assertTrue(toolResults.get(0).result().output().contains("phase-5"));
  }

  @Test
  void denyRuleBlocksBashCallToForbiddenBinary() throws Exception {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());

    var turns =
        List.<List<ModelChunk>>of(
            List.of(
                new ModelChunk.ToolUseStop(
                    new ToolCall(
                        "c1",
                        ExecuteTool.NAME,
                        Map.of("runtime", "BASH", "script", "rm -rf /tmp/should-not-happen"))),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(1, 1))),
            List.of(
                new ModelChunk.TextDelta("done"),
                new ModelChunk.MessageStop("STOP", Usage.of(1, 1))));

    var permission =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(PermissionRule.any(PermissionEffect.ALLOW, ExecuteTool.NAME)),
            List.of(),
            List.of(PermissionRule.withGlob(PermissionEffect.DENY, ExecuteTool.NAME, "BASH/rm")));

    var options =
        SessionOptions.newBuilder()
            .withModel(new ScriptedModel(turns))
            .withSessionId("phase5-deny-" + UUID.randomUUID())
            .withTools(new ToolRegistry(List.of(ExecuteTool.binding(provider))))
            .withExecutionProvider(provider)
            .withPermission(permission)
            .build();

    var events = new CopyOnWriteArrayList<QueryEvent>();
    var done = new CountDownLatch(1);
    try (var session = AgentSession.create(options)) {
      session.events().subscribe(collectingSubscriber(events, done));
      session.runBlocking(UserMessage.text("try a forbidden command"));
      assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    var blocked =
        events.stream()
            .filter(e -> e instanceof QueryEvent.ToolBlocked)
            .map(e -> (QueryEvent.ToolBlocked) e)
            .findFirst()
            .orElse(null);
    assertNotNull(blocked, "expected Execute(BASH/rm) to be blocked by deny rule");
    assertEquals(ExecuteTool.NAME, blocked.call().name());
  }

  @Test
  void slowBashCallAgainstTightTimeoutSurfacesTimedOutTrue() throws Exception {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());

    // Model asks for a sleep that exceeds the per-execution timeout; ExecuteTool surfaces
    // timedOut=true in the structured tool result.
    var turns =
        List.<List<ModelChunk>>of(
            List.of(
                new ModelChunk.ToolUseStop(
                    new ToolCall(
                        "c1",
                        ExecuteTool.NAME,
                        Map.of("runtime", "BASH", "script", "sleep 5", "timeoutSeconds", 1))),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(1, 1))),
            List.of(
                new ModelChunk.TextDelta("done"),
                new ModelChunk.MessageStop("STOP", Usage.of(1, 1))));

    var permission =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(PermissionRule.any(PermissionEffect.ALLOW, ExecuteTool.NAME)),
            List.of(),
            List.of());

    var options =
        SessionOptions.newBuilder()
            .withModel(new ScriptedModel(turns))
            .withSessionId("phase5-timeout-" + UUID.randomUUID())
            .withTools(new ToolRegistry(List.of(ExecuteTool.binding(provider))))
            .withExecutionProvider(provider)
            .withPermission(permission)
            .build();

    var events = new CopyOnWriteArrayList<QueryEvent>();
    try (var session = AgentSession.create(options)) {
      session.events().subscribe(collectingSubscriber(events, null));
      var result = session.runBlocking(UserMessage.text("trigger timeout"));
      assertInstanceOf(ResultMessage.Success.class, result);
    }

    var toolResult =
        events.stream()
            .filter(e -> e instanceof QueryEvent.ToolResult)
            .map(e -> (QueryEvent.ToolResult) e)
            .findFirst()
            .orElseThrow();
    var execResult = assertInstanceOf(ExecutionResult.class, toolResult.result().data());
    assertTrue(execResult.timedOut(), "expected timedOut=true on the structured result");
    assertEquals(-1, execResult.exitCode());
    assertTrue(toolResult.result().output().contains("TIMEOUT"));
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static Flow.Subscriber<QueryEvent> collectingSubscriber(
      List<QueryEvent> sink, CountDownLatch done) {
    return new Flow.Subscriber<>() {
      @Override
      public void onSubscribe(Flow.Subscription s) {
        s.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(QueryEvent ev) {
        sink.add(ev);
      }

      @Override
      public void onError(Throwable t) {
        if (done != null) {
          done.countDown();
        }
      }

      @Override
      public void onComplete() {
        if (done != null) {
          done.countDown();
        }
      }
    };
  }

  private static void assumeBashAvailable() {
    assumeTrue(
        Files.isExecutable(Path.of("/bin/bash")) || Files.isExecutable(Path.of("/usr/bin/bash")),
        "bash is not available; skipping Phase 5 BASH scenarios");
  }

  private static void assumePythonAvailable() {
    try {
      CommandGrant.resolveBinary("python3", System.getenv("PATH"));
    } catch (RuntimeException e) {
      assumeTrue(false, "python3 is not available; skipping Phase 5 PYTHON scenarios");
    }
  }
}
