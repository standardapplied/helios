/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.gemini.GeminiModelId;
import com.standardapplied.helios.gemini.GeminiProvider;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.files.GlobTool;
import com.standardapplied.helios.session.files.GrepTool;
import com.standardapplied.helios.session.files.LsTool;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.memory.FileSystemMemoryBackend;
import com.standardapplied.helios.session.memory.MemoryWriteTool;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.test.QuestionAnswers;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end integration of the Helios session SDK against the Gemini API.
 *
 * <p>Tests the same shape as {@link SessionDemoMain} but with assertions instead of console output.
 * Skipped when {@code GEMINI_API_KEY} is unset so the suite stays runnable offline.
 *
 * <p>Assertions describe what the framework guarantees whatever the model writes: forced to call a
 * tool, the loop dispatches it and the provider accepts the next turn; the attachment-bearing user
 * message is accepted; and a memory write without an allow rule asks the user, who denies it, so
 * the permission system blocks it before dispatch and it never reaches disk. Each forced session
 * names the tools under test, so every call it makes is one of them.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
final class SessionDemoIntegrationTest {

  private static final List<String> FILE_READ_TOOLS =
      List.of(ReadTool.NAME, LsTool.NAME, GlobTool.NAME, GrepTool.NAME);

  private static Model model;

  @BeforeAll
  static void setUp() {
    model = gemini(ModelConfig.newBuilder());
  }

  private static Model gemini(ModelConfig.Builder config) {
    return new GeminiProvider()
        .create(
            GeminiModelId.GEMINI_3_8_FLASH.id(),
            config.withApiKey(System.getenv("GEMINI_API_KEY")).build());
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (model != null) {
      model.close();
    }
  }

  @Test
  void agentExploresRepoWithItsFileTools(@TempDir Path tmp) throws Exception {
    seedFakeRepo(tmp);
    var fileTools = ToolChoice.required(FILE_READ_TOOLS.toArray(String[]::new));
    try (var forced = gemini(ModelConfig.newBuilder().withToolChoice(fileTools));
        var session =
            AgentSession.open(
                SessionDemoMain.exploreAndRememberOptions(forced, tmp)
                    .withLimits(SessionLimits.newBuilder().withMaxTurns(3).build())
                    .build())) {
      var events = new EventCollector(QuestionAnswers.selecting(session, "Deny"));
      events.read(session.events());
      var prompt =
          "Do exactly two steps, then stop:\n"
              + "1. Call LS on the workspace root to list its contents.\n"
              + "2. Call Read on README.md.";
      var result = session.runBlocking(UserMessage.text(prompt));
      events.awaitRunEnded();

      assertTrue(
          result instanceof ResultMessage.Success || result instanceof ResultMessage.ErrorMaxTurns,
          () -> "session did not reach a clean terminal: " + result);
      var failedToolEvents = events.eventsOf(QueryEvent.Error.class);
      assertTrue(
          failedToolEvents.isEmpty(),
          () -> "no provider-level errors expected, got " + failedToolEvents);
      var toolNamesObserved =
          events.eventsOf(QueryEvent.ToolResult.class).stream().map(e -> e.call().name()).toList();
      assertFalse(toolNamesObserved.isEmpty());
      assertTrue(FILE_READ_TOOLS.containsAll(toolNamesObserved), toolNamesObserved::toString);
    }
  }

  @Test
  void agentAcceptsImageAttachment(@TempDir Path tmp) throws Exception {
    var pngPath = tmp.resolve("color.png");
    writeSamplePng(pngPath);
    var options =
        SessionOptions.newBuilder().withModel(model).withSessionId("session-demo-att-test").build();

    try (var session = AgentSession.open(options)) {
      new EventCollector(QuestionAnswers.selecting(session, "Deny")).read(session.events());
      var msg =
          UserMessage.newBuilder()
              .withText("I'm sending a small generated image. Briefly describe what you see.")
              .withAttachment(pngPath)
              .build();
      var result = session.runBlocking(msg);

      var success =
          assertInstanceOf(
              ResultMessage.Success.class,
              result,
              () -> "attachment session did not finish Success: " + result);
      assertNotNull(success.result());
    }
  }

  @Test
  void agentMemoryWriteBlockedWithoutExplicitAllow(@TempDir Path tmp) throws Exception {
    seedFakeRepo(tmp);
    var ws = WorkspaceRoot.of(tmp);
    var memoryBackend = FileSystemMemoryBackend.of(ws);
    var permission =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(PermissionRule.of("MemoryRead", "/memories/**")),
            List.of(),
            List.of());
    EventCollector events;
    var memoryWrite = ToolChoice.required(MemoryWriteTool.NAME);
    try (var forced = gemini(ModelConfig.newBuilder().withToolChoice(memoryWrite));
        var session =
            AgentSession.open(
                SessionOptions.newBuilder()
                    .withModel(forced)
                    .withPermission(permission)
                    .withTools(List.of(MemoryWriteTool.binding(memoryBackend)))
                    .withLimits(SessionLimits.newBuilder().withMaxTurns(2).build())
                    .build())) {
      events = new EventCollector(QuestionAnswers.selecting(session, "Deny"));
      events.read(session.events());
      session.runBlocking(
          UserMessage.text(
              "Use MemoryWrite with op=create to save a note at /memories/test.md with content"
                  + " 'hello world'."));
      events.awaitRunEnded();
    }

    var calls = events.eventsOf(QueryEvent.ToolResult.class);
    assertFalse(calls.isEmpty());
    assertTrue(
        calls.stream().allMatch(result -> result.call().name().equals(MemoryWriteTool.NAME)),
        calls::toString);
    assertTrue(
        events.eventsOf(QueryEvent.ToolBlocked.class).stream()
            .anyMatch(block -> block.call().name().equals(MemoryWriteTool.NAME)),
        "the MemoryWrite must be blocked");
    var successfulMemoryWrites =
        events.eventsOf(QueryEvent.ToolResult.class).stream()
            .filter(r -> r.call().name().equals(MemoryWriteTool.NAME))
            .filter(r -> r.result().succeeded())
            .count();
    assertEquals(0L, successfulMemoryWrites);
    assertTrue(
        memoryBackend.list("/memories/").isEmpty(), "no memory entries should have been written");
  }

  private static void seedFakeRepo(Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("README.md"),
        "# Helios Demo Repo\n\nA tiny three-file repo for the session SDK demo.\n",
        StandardCharsets.UTF_8);
    Files.createDirectories(tmp.resolve("src/main/java/demo"));
    Files.writeString(
        tmp.resolve("src/main/java/demo/Main.java"),
        """
        package demo;
        public final class Main {
          public static void main(String[] args) {
            System.out.println("hello");
          }
        }
        """,
        StandardCharsets.UTF_8);
    Files.writeString(
        tmp.resolve("notes.md"), "Project status: experimental\n", StandardCharsets.UTF_8);
  }

  private static void writeSamplePng(Path target) throws IOException {
    var img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
    for (var y = 0; y < 16; y++) {
      for (var x = 0; x < 16; x++) {
        img.setRGB(x, y, ((x * 16) << 16) | ((y * 16) << 8) | ((x + y) * 8));
      }
    }
    ImageIO.write(img, "PNG", target.toFile());
  }
}
