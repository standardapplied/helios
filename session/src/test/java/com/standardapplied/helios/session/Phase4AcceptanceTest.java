/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelChunk;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.ask.AskUserQuestionResponse;
import ai.singlr.session.files.WorkspaceRoot;
import ai.singlr.session.memory.FileSystemMemoryBackend;
import ai.singlr.session.memory.MemoryReadTool;
import ai.singlr.session.memory.MemoryWriteTool;
import ai.singlr.session.permissions.Permission;
import ai.singlr.session.permissions.PermissionEffect;
import ai.singlr.session.permissions.PermissionMode;
import ai.singlr.session.permissions.PermissionRule;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 4 acceptance: the agent reads a memory file, creates a new one, edits it via str_replace,
 * and finally lists the directory to confirm. Exercises auto-registration of MemoryRead +
 * MemoryWrite, the permission allow rule for {@code MemoryWrite(/memories/**)}, and the four write
 * ops via a scripted model.
 */
final class Phase4AcceptanceTest {

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
      return "phase4-script";
    }

    @Override
    public String provider() {
      return "test";
    }
  }

  @Test
  void agentReadsCreatesAndEditsMemory(@TempDir Path tmp) throws Exception {
    var workspace = WorkspaceRoot.of(tmp);
    var backend = FileSystemMemoryBackend.of(workspace);
    backend.create("/memories/INDEX.md", "- nothing yet\n");

    // Permission: ALLOW MemoryRead + MemoryWrite under /memories/**, default-deny WRITE elsewhere.
    var permission =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(
                PermissionRule.withGlob(PermissionEffect.ALLOW, "MemoryRead", "/memories/**"),
                PermissionRule.withGlob(PermissionEffect.ALLOW, "MemoryWrite", "/memories/**")),
            List.of(),
            List.of());

    // 4-turn script:
    //   1: read INDEX.md
    //   2: create new note
    //   3: str_replace INDEX.md to register it
    //   4: terminal text
    var turns =
        List.<List<ModelChunk>>of(
            List.of(
                new ModelChunk.ToolUseStop(
                    new ToolCall("c1", MemoryReadTool.NAME, Map.of("path", "/memories/INDEX.md"))),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(1, 1))),
            List.of(
                new ModelChunk.ToolUseStop(
                    new ToolCall(
                        "c2",
                        MemoryWriteTool.NAME,
                        Map.of(
                            "op",
                            "create",
                            "path",
                            "/memories/user/preferences.md",
                            "content",
                            "User prefers terse responses.\n"))),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(1, 1))),
            List.of(
                new ModelChunk.ToolUseStop(
                    new ToolCall(
                        "c3",
                        MemoryWriteTool.NAME,
                        Map.of(
                            "op",
                            "str_replace",
                            "path",
                            "/memories/INDEX.md",
                            "oldString",
                            "- nothing yet",
                            "newString",
                            "- /memories/user/preferences.md"))),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(1, 1))),
            List.of(
                new ModelChunk.TextDelta("done"),
                new ModelChunk.MessageStop("STOP", Usage.of(1, 1))));

    var options =
        SessionOptions.newBuilder()
            .withModel(new ScriptedModel(turns))
            .withSessionId("phase4-" + UUID.randomUUID())
            .withMemoryBackend(backend)
            .withPermission(permission)
            .build();

    var events = new CopyOnWriteArrayList<QueryEvent>();
    var done = new CountDownLatch(1);

    try (var session = AgentSession.create(options)) {
      session
          .events()
          .subscribe(
              new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                  s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(QueryEvent ev) {
                  events.add(ev);
                }

                @Override
                public void onError(Throwable t) {
                  done.countDown();
                }

                @Override
                public void onComplete() {
                  done.countDown();
                }
              });

      var result = session.runBlocking(UserMessage.text("update my preferences"));
      assertTrue(done.await(5, TimeUnit.SECONDS));
      assertInstanceOf(ResultMessage.Success.class, result);
    }

    // Verify final state on disk.
    assertEquals("User prefers terse responses.\n", backend.view("/memories/user/preferences.md"));
    assertEquals("- /memories/user/preferences.md\n", backend.view("/memories/INDEX.md"));

    // Verify all three tool calls succeeded.
    var toolResults =
        events.stream()
            .filter(e -> e instanceof QueryEvent.ToolResult)
            .map(e -> (QueryEvent.ToolResult) e)
            .toList();
    assertEquals(3, toolResults.size());
    for (var r : toolResults) {
      assertTrue(r.result().success(), r.call().name() + " failed: " + r.result().output());
    }
    assertEquals(MemoryReadTool.NAME, toolResults.get(0).call().name());
    assertEquals(MemoryWriteTool.NAME, toolResults.get(1).call().name());
    assertEquals(MemoryWriteTool.NAME, toolResults.get(2).call().name());
  }

  @Test
  void memoryWriteAllowedWhenUserAllowsAsk(@TempDir Path tmp) throws Exception {
    var workspace = WorkspaceRoot.of(tmp);
    var backend = FileSystemMemoryBackend.of(workspace);

    // No explicit MemoryWrite allow rule — falls to ASK. Our subscriber answers "Allow", so the
    // call goes through and the file lands on disk.
    var permission =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(PermissionRule.withGlob(PermissionEffect.ALLOW, "MemoryRead", "/memories/**")),
            List.of(),
            List.of());

    var turns =
        List.<List<ModelChunk>>of(
            List.of(
                new ModelChunk.ToolUseStop(
                    new ToolCall(
                        "c1",
                        MemoryWriteTool.NAME,
                        Map.of("op", "create", "path", "/memories/x.md", "content", "permitted"))),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(1, 1))),
            List.of(
                new ModelChunk.TextDelta("ok"),
                new ModelChunk.MessageStop("STOP", Usage.of(1, 1))));

    var options =
        SessionOptions.newBuilder()
            .withModel(new ScriptedModel(turns))
            .withSessionId("phase4-allow-" + UUID.randomUUID())
            .withMemoryBackend(backend)
            .withPermission(permission)
            .build();

    var events = new CopyOnWriteArrayList<QueryEvent>();
    try (var session = AgentSession.create(options)) {
      session
          .events()
          .subscribe(
              new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                  s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(QueryEvent ev) {
                  events.add(ev);
                  if (ev instanceof QueryEvent.QuestionAsked qa) {
                    session.answer(
                        qa.request().questionId(),
                        AskUserQuestionResponse.single(qa.request().questionId(), "Allow"));
                  }
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onComplete() {}
              });

      var result = session.runBlocking(UserMessage.text("try to write"));
      assertInstanceOf(ResultMessage.Success.class, result);
    }

    // Verify the question fired.
    var question =
        events.stream().filter(e -> e instanceof QueryEvent.QuestionAsked).findFirst().orElse(null);
    assertTrue(question != null, "expected a QuestionAsked event from the ASK fallback");
    // Verify the write went through.
    assertEquals("permitted", backend.view("/memories/x.md"));
  }

  @Test
  void memoryWriteIsBlockedWhenUserDeniesAsk(@TempDir Path tmp) throws Exception {
    var workspace = WorkspaceRoot.of(tmp);
    var backend = FileSystemMemoryBackend.of(workspace);

    // No explicit MemoryWrite allow rule. Under DEFAULT mode, WRITE category falls to ASK; the
    // session's QuestionGateway surfaces an AskUserQuestion. Our subscriber answers "Deny" so the
    // permission system blocks the call.
    var permission =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(PermissionRule.withGlob(PermissionEffect.ALLOW, "MemoryRead", "/memories/**")),
            List.of(),
            List.of());

    var turns =
        List.<List<ModelChunk>>of(
            List.of(
                new ModelChunk.ToolUseStop(
                    new ToolCall(
                        "c1",
                        MemoryWriteTool.NAME,
                        Map.of("op", "create", "path", "/memories/x.md", "content", "x"))),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(1, 1))),
            List.of(
                new ModelChunk.TextDelta("ok"),
                new ModelChunk.MessageStop("STOP", Usage.of(1, 1))));

    var options =
        SessionOptions.newBuilder()
            .withModel(new ScriptedModel(turns))
            .withSessionId("phase4-deny-" + UUID.randomUUID())
            .withMemoryBackend(backend)
            .withPermission(permission)
            .build();

    var events = new CopyOnWriteArrayList<QueryEvent>();
    var done = new CountDownLatch(1);

    try (var session = AgentSession.create(options)) {
      session
          .events()
          .subscribe(
              new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                  s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(QueryEvent ev) {
                  events.add(ev);
                  if (ev instanceof QueryEvent.QuestionAsked qa) {
                    session.answer(
                        qa.request().questionId(),
                        AskUserQuestionResponse.single(qa.request().questionId(), "Deny"));
                  }
                }

                @Override
                public void onError(Throwable t) {
                  done.countDown();
                }

                @Override
                public void onComplete() {
                  done.countDown();
                }
              });

      session.runBlocking(UserMessage.text("try to write"));
      assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    var blocked =
        events.stream()
            .filter(e -> e instanceof QueryEvent.ToolBlocked)
            .map(e -> (QueryEvent.ToolBlocked) e)
            .findFirst()
            .orElse(null);
    assertTrue(blocked != null, "expected MemoryWrite to be blocked");
    assertEquals(MemoryWriteTool.NAME, blocked.call().name());
    // And nothing should have been written to disk.
    assertEquals(List.<String>of(), backend.list("/memories/"));
  }

  @Test
  void closeWhileQuestionIsPendingUnblocksGatewayAsCancellation(@TempDir Path tmp)
      throws Exception {
    // A pending AskUserQuestion blocks the gateway's future.get(); closing the session must
    // signal the cancellation token, which now triggers the onCancel callback that completes
    // the future exceptionally. The gateway translates that into a tool-failure ToolResult,
    // letting the loop terminate cleanly as Cancelled rather than hanging on the future.
    var workspace = WorkspaceRoot.of(tmp);
    var backend = FileSystemMemoryBackend.of(workspace);

    var permission =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(PermissionRule.withGlob(PermissionEffect.ALLOW, "MemoryRead", "/memories/**")),
            List.of(),
            List.of());

    var turns =
        List.<List<ModelChunk>>of(
            List.of(
                new ModelChunk.ToolUseStop(
                    new ToolCall(
                        "c1",
                        MemoryWriteTool.NAME,
                        Map.of("op", "create", "path", "/memories/x.md", "content", "x"))),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(1, 1))));

    var options =
        SessionOptions.newBuilder()
            .withModel(new ScriptedModel(turns))
            .withSessionId("phase4-cancel-" + UUID.randomUUID())
            .withMemoryBackend(backend)
            .withPermission(permission)
            .build();

    var questionLatch = new CountDownLatch(1);
    var session = AgentSession.create(options);
    session
        .events()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(QueryEvent ev) {
                if (ev instanceof QueryEvent.QuestionAsked) {
                  questionLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onComplete() {}
            });
    session.send(UserMessage.text("trigger write"));
    assertTrue(questionLatch.await(5, TimeUnit.SECONDS), "agent must reach the ASK question");
    session.close();
    var terminal = session.result().get(5, TimeUnit.SECONDS);
    assertInstanceOf(ResultMessage.Cancelled.class, terminal);
    var _unused = Optional.empty();
  }
}
