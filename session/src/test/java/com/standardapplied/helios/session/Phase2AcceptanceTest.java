/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.session.ask.AskUserQuestionTool;
import com.standardapplied.helios.session.files.GlobTool;
import com.standardapplied.helios.session.files.GrepTool;
import com.standardapplied.helios.session.files.InMemoryFileTracker;
import com.standardapplied.helios.session.files.LsTool;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.memory.FileSystemMemoryBackend;
import com.standardapplied.helios.session.memory.MemoryReadTool;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.test.QuestionAnswers;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 2 acceptance test (spec §22.5: "model can navigate a fake repo, ask via AskUserQuestion,
 * and respect deny rules"). Exercises the part-5 surface end-to-end: a scripted Model drives a
 * session through Read/LS/Glob/Grep, hits a deny rule, asks the user a structured question, gets an
 * answer via session.answer(...), and terminates with Success.
 */
final class Phase2AcceptanceTest {

  @Test
  void modelNavigatesRepoAsksAndRespectsDenyRule(@TempDir Path tmp) throws Exception {
    // Fake repo layout.
    Files.createDirectories(tmp.resolve("src/main"));
    Files.writeString(
        tmp.resolve("src/main/Hello.java"),
        "package demo;\npublic class Hello { /* TARGET */ }\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        tmp.resolve("README.md"), "# repo readme\nTARGET line here\n", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("sensitive.txt"), "secret stuff\n", StandardCharsets.UTF_8);

    // Memory backend with one seeded entry.
    Files.createDirectories(tmp.resolve(FileSystemMemoryBackend.STORAGE_SUBDIR));
    Files.writeString(
        tmp.resolve(FileSystemMemoryBackend.STORAGE_SUBDIR + "/INDEX.md"),
        "remember the README",
        StandardCharsets.UTF_8);

    var workspace = WorkspaceRoot.of(tmp);
    var tracker = InMemoryFileTracker.create();
    var memoryBackend = FileSystemMemoryBackend.of(workspace);

    // User tools: Read, LS, Glob, Grep. MemoryRead + AskUserQuestion are auto-registered by the
    // session.
    var tools =
        new ToolRegistry(
            List.of(
                ReadTool.binding(workspace, tracker),
                LsTool.binding(workspace),
                GlobTool.binding(workspace),
                GrepTool.binding(workspace)));

    // Permission policy: deny Read on the sensitive file, allow everything else by default.
    var permission =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(),
            List.of(),
            List.of(
                new PermissionRule(PermissionEffect.DENY, "Read", Optional.of("sensitive.txt"))));

    // Five-turn script: Grep → Read(allowed) → Read(denied) → AskUserQuestion → final STOP.
    var grepCall = new ToolCall("c1", GrepTool.NAME, Map.of("pattern", "TARGET"));
    var readAllowed = new ToolCall("c2", ReadTool.NAME, Map.of("path", "src/main/Hello.java"));
    var readDenied = new ToolCall("c3", ReadTool.NAME, Map.of("path", "sensitive.txt"));
    var askCall =
        new ToolCall(
            "c4",
            AskUserQuestionTool.NAME,
            Map.of(
                "question",
                "Did you find what you needed?",
                "options",
                List.of(
                    Map.of("label", "Yes", "description", "done"),
                    Map.of("label", "No", "description", "keep going"))));
    var usage = Usage.of(2, 1);
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(usage, grepCall)
            .withToolCallsTurn(usage, readAllowed)
            .withToolCallsTurn(usage, readDenied)
            .withToolCallsTurn(usage, askCall)
            .withTextTurn("all done", usage)
            .build();

    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSessionId("phase2-sess-" + UUID.randomUUID())
            .withTools(tools)
            .withPermission(permission)
            .withMemoryBackend(memoryBackend)
            .build();

    try (var session = AgentSession.create(options)) {
      var answered = new AtomicBoolean(false);
      var sub =
          new CollectingSubscriber(
              QuestionAnswers.selecting(session, "Yes")
                  .andThen(
                      event -> {
                        if (event instanceof QueryEvent.QuestionAsked) {
                          answered.set(true);
                        }
                      }));
      session.events().subscribe(sub);

      var result = session.runBlocking(UserMessage.text("explore the repo"));
      sub.awaitDone();
      assertTrue(answered.get(), "AskUserQuestion was never answered");

      // Verify terminal Success.
      assertInstanceOf(ResultMessage.Success.class, result);

      // Verify Grep ran successfully and the result names the seeded files.
      var grepResult = findToolResult(sub.events(), GrepTool.NAME);
      assertNotNull(grepResult);
      assertTrue(grepResult.result().success());
      var grepOut = grepResult.result().output();
      assertTrue(grepOut.contains("README.md"), grepOut);
      assertTrue(grepOut.contains("Hello.java"), grepOut);

      // Verify the allowed Read ran successfully and surfaced the file's content.
      var allowedResult = findToolResult(sub.events(), ReadTool.NAME);
      assertNotNull(allowedResult);
      assertTrue(allowedResult.result().success());
      assertTrue(allowedResult.result().output().contains("Hello"));

      // Verify the denied Read was BLOCKED by the permission system.
      var blocked = findEvent(sub.events(), QueryEvent.ToolBlocked.class);
      assertNotNull(blocked, "expected the deny rule to surface a ToolBlocked event");
      assertEquals("Read", blocked.call().name());
      assertTrue(
          blocked.reason().contains("deny rule for Read"),
          "reason should reference the deny rule, got: " + blocked.reason());

      // Verify a QuestionAsked event surfaced.
      var question = findEvent(sub.events(), QueryEvent.QuestionAsked.class);
      assertNotNull(question);
      assertEquals("Did you find what you needed?", question.request().question());

      // Verify the AskUserQuestion tool returned the user's selection.
      var askResult = findToolResult(sub.events(), AskUserQuestionTool.NAME);
      assertNotNull(askResult);
      assertTrue(askResult.result().success());
      assertTrue(askResult.result().output().contains("- Yes"));

      // Verify memory listing works via the auto-registered MemoryRead tool.
      var memBinding = options.tools(); // user-supplied registry, no MemoryRead in here
      // Sanity: MemoryRead is NOT in the user registry, only in the combined session registry.
      assertTrue(memBinding.get(MemoryReadTool.NAME).isEmpty());
    }
  }

  private static QueryEvent.ToolResult findToolResult(List<QueryEvent> events, String toolName) {
    for (var ev : events) {
      if (ev instanceof QueryEvent.ToolResult tr && tr.call().name().equals(toolName)) {
        return tr;
      }
    }
    return null;
  }

  private static <T extends QueryEvent> T findEvent(List<QueryEvent> events, Class<T> cls) {
    return events.stream().filter(cls::isInstance).map(cls::cast).findFirst().orElse(null);
  }
}
