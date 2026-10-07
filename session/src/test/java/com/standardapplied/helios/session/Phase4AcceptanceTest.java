/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.memory.FileSystemMemoryBackend;
import com.standardapplied.helios.session.memory.MemoryReadTool;
import com.standardapplied.helios.session.memory.MemoryWriteTool;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.test.QuestionAnswers;
import com.standardapplied.helios.testing.ScriptedModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 4 acceptance: the agent reads a memory file, creates a new one, edits it via str_replace,
 * and finally lists the directory to confirm. Exercises auto-registration of MemoryRead +
 * MemoryWrite, the permission allow rule for {@code MemoryWrite(/memories/**)}, and the four write
 * ops via a scripted model.
 */
final class Phase4AcceptanceTest {

  private static final Usage USAGE = Usage.of(1, 1);

  private static Permission allowing(PermissionRule... rules) {
    return new Permission(PermissionMode.DEFAULT, List.of(rules), List.of(), List.of());
  }

  private static PermissionRule allowUnderMemories(String tool) {
    return PermissionRule.withGlob(PermissionEffect.ALLOW, tool, "/memories/**");
  }

  private static SessionOptions options(
      String sessionId, ScriptedModel model, FileSystemMemoryBackend backend, Permission rules) {
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId(sessionId + UUID.randomUUID())
        .withMemoryBackend(backend)
        .withPermission(rules)
        .build();
  }

  /** A model that calls MemoryWrite to create {@code /memories/x.md} holding {@code content}. */
  private static ScriptedModel.Builder creatingMemory(String content) {
    return ScriptedModel.newBuilder()
        .withToolCallsTurn(
            USAGE,
            new ToolCall(
                "c1",
                MemoryWriteTool.NAME,
                Map.of("op", "create", "path", "/memories/x.md", "content", content)));
  }

  @Test
  void agentReadsCreatesAndEditsMemory(@TempDir Path tmp) throws Exception {
    var workspace = WorkspaceRoot.of(tmp);
    var backend = FileSystemMemoryBackend.of(workspace);
    backend.create("/memories/INDEX.md", "- nothing yet\n");

    // Permission: ALLOW MemoryRead + MemoryWrite under /memories/**, default-deny WRITE elsewhere.
    var permission = allowing(allowUnderMemories("MemoryRead"), allowUnderMemories("MemoryWrite"));

    // 4-turn script:
    //   1: read INDEX.md
    //   2: create new note
    //   3: str_replace INDEX.md to register it
    //   4: terminal text
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(
                USAGE,
                new ToolCall("c1", MemoryReadTool.NAME, Map.of("path", "/memories/INDEX.md")))
            .withToolCallsTurn(
                USAGE,
                new ToolCall(
                    "c2",
                    MemoryWriteTool.NAME,
                    Map.of(
                        "op",
                        "create",
                        "path",
                        "/memories/user/preferences.md",
                        "content",
                        "User prefers terse responses.\n")))
            .withToolCallsTurn(
                USAGE,
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
                        "- /memories/user/preferences.md")))
            .withTextTurn("done", USAGE)
            .build();

    var sub = new CollectingSubscriber();
    try (var session = AgentSession.create(options("phase4-", model, backend, permission))) {
      session.events().subscribe(sub);
      var result = session.runBlocking(UserMessage.text("update my preferences"));
      sub.awaitDone();
      assertInstanceOf(ResultMessage.Success.class, result);
    }

    // Verify final state on disk.
    assertEquals("User prefers terse responses.\n", backend.view("/memories/user/preferences.md"));
    assertEquals("- /memories/user/preferences.md\n", backend.view("/memories/INDEX.md"));

    // Verify all three tool calls succeeded.
    var toolResults = sub.eventsOf(QueryEvent.ToolResult.class);
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
    var model = creatingMemory("permitted").withTextTurn("ok", USAGE).build();
    var options =
        options("phase4-allow-", model, backend, allowing(allowUnderMemories("MemoryRead")));

    CollectingSubscriber sub;
    try (var session = AgentSession.create(options)) {
      sub = new CollectingSubscriber(QuestionAnswers.selecting(session, "Allow"));
      session.events().subscribe(sub);

      var result = session.runBlocking(UserMessage.text("try to write"));
      assertInstanceOf(ResultMessage.Success.class, result);
    }

    // Verify the question fired.
    assertFalse(
        sub.eventsOf(QueryEvent.QuestionAsked.class).isEmpty(),
        "expected a QuestionAsked event from the ASK fallback");
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
    var model = creatingMemory("x").withTextTurn("ok", USAGE).build();
    var options =
        options("phase4-deny-", model, backend, allowing(allowUnderMemories("MemoryRead")));

    CollectingSubscriber sub;
    try (var session = AgentSession.create(options)) {
      sub = new CollectingSubscriber(QuestionAnswers.selecting(session, "Deny"));
      session.events().subscribe(sub);

      session.runBlocking(UserMessage.text("try to write"));
      sub.awaitDone();
    }

    var blocked = sub.eventsOf(QueryEvent.ToolBlocked.class);
    assertFalse(blocked.isEmpty(), "expected MemoryWrite to be blocked");
    assertEquals(MemoryWriteTool.NAME, blocked.getFirst().call().name());
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
    var model = creatingMemory("x").build();
    var options =
        options("phase4-cancel-", model, backend, allowing(allowUnderMemories("MemoryRead")));

    var questionLatch = new CountDownLatch(1);
    var session = AgentSession.create(options);
    session
        .events()
        .subscribe(
            new CollectingSubscriber(
                event -> {
                  if (event instanceof QueryEvent.QuestionAsked) {
                    questionLatch.countDown();
                  }
                }));
    session.send(UserMessage.text("trigger write"));
    Await.latch("the agent to reach the ASK question", questionLatch);
    session.close();
    var terminal = Await.value("the closed session to settle its result", session.result());
    assertInstanceOf(ResultMessage.Cancelled.class, terminal);
  }
}
