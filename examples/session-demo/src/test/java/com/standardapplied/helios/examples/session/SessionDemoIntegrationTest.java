/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */
package ai.singlr.examples.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.gemini.GeminiModelId;
import ai.singlr.gemini.GeminiProvider;
import ai.singlr.session.AgentSession;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.ResultMessage;
import ai.singlr.session.SessionLimits;
import ai.singlr.session.SessionOptions;
import ai.singlr.session.UserMessage;
import ai.singlr.session.files.GlobTool;
import ai.singlr.session.files.GrepTool;
import ai.singlr.session.files.InMemoryFileTracker;
import ai.singlr.session.files.LsTool;
import ai.singlr.session.files.ReadTool;
import ai.singlr.session.files.WorkspaceRoot;
import ai.singlr.session.memory.FileSystemMemoryBackend;
import ai.singlr.session.memory.MemoryWriteTool;
import ai.singlr.session.permissions.Permission;
import ai.singlr.session.permissions.PermissionEffect;
import ai.singlr.session.permissions.PermissionMode;
import ai.singlr.session.permissions.PermissionRule;
import ai.singlr.session.tools.ToolRegistry;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
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
 * <p>Assertions describe what the framework guarantees given a cooperating model — the model fires
 * at least one file-reading tool, the loop terminates with a non-null result, and the
 * attachment-bearing user message reaches the provider without crashing the encoder. Specific
 * tool-call ordering is non-deterministic and not asserted.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
final class SessionDemoIntegrationTest {

  private static final Set<String> FILE_READ_TOOLS =
      Set.of(ReadTool.NAME, LsTool.NAME, GlobTool.NAME, GrepTool.NAME);

  private static Model model;

  @BeforeAll
  static void setUp() {
    var apiKey = System.getenv("GEMINI_API_KEY");
    var config = ModelConfig.newBuilder().withApiKey(apiKey).build();
    model = new GeminiProvider().create(GeminiModelId.GEMINI_3_5_FLASH.id(), config);
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (model != null) {
      model.close();
    }
  }

  @Test
  void agentExploresRepoAndWritesToMemory(@TempDir Path tmp) throws Exception {
    seedFakeRepo(tmp);
    var ws = WorkspaceRoot.of(tmp);
    var tracker = InMemoryFileTracker.create();
    var memoryBackend = FileSystemMemoryBackend.of(ws);
    var tools =
        new ToolRegistry(
            List.of(
                ReadTool.binding(ws, tracker),
                LsTool.binding(ws),
                GlobTool.binding(ws),
                GrepTool.binding(ws)));
    var permission =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(
                PermissionRule.withGlob(PermissionEffect.ALLOW, "MemoryRead", "/memories/**"),
                PermissionRule.withGlob(PermissionEffect.ALLOW, "MemoryWrite", "/memories/**")),
            List.of(),
            List.of());
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withTools(tools)
            .withPermission(permission)
            .withMemoryBackend(memoryBackend)
            .withLimits(SessionLimits.newBuilder().withMaxTurns(12).build())
            .build();

    var events = new CopyOnWriteArrayList<QueryEvent>();
    try (var session = AgentSession.create(options)) {
      session.events().subscribe(collector(events));
      // Directive prompt: each tool used at most once. An open-ended "look at the repo" prompt
      // gives Gemini Flash too much rope and it loops re-running LS forever. maxTurns=12 above is
      // the belt-and-braces ceiling so a misbehaving model still terminates the test in seconds.
      var prompt =
          "Do exactly three steps, then stop:\n"
              + "1. Call LS on the workspace root to list its contents.\n"
              + "2. Call Read on README.md.\n"
              + "3. Call MemoryWrite with op=create, path=/memories/project/summary.md, and a"
              + " one-line content summary based on what you just read.\n"
              + "After step 3, reply with a short confirmation. Do NOT explore further.";
      var result = session.runBlocking(UserMessage.text(prompt));

      // The framework guarantees we care about here:
      //   - The agent loop reaches a defined terminal state (no hang, no provider crash). Both
      //     Success and ErrorMaxTurns are well-defined terminals — which one we hit depends on
      //     how directive the model decides to be, and is not what this test pins down.
      //   - At least one file-read tool fired (workspace tool registration + dispatch works).
      //   - No provider-level Error event fired (the multi-turn wire round-trip is clean).
      // The model picking its own exploration depth is not a framework concern.
      assertTrue(
          result instanceof ResultMessage.Success || result instanceof ResultMessage.ErrorMaxTurns,
          () -> "session did not reach a clean terminal: " + result);

      var toolNamesObserved =
          events.stream()
              .filter(e -> e instanceof QueryEvent.ToolUse)
              .map(e -> ((QueryEvent.ToolUse) e).call().name())
              .toList();
      assertTrue(
          toolNamesObserved.stream().anyMatch(FILE_READ_TOOLS::contains),
          () -> "expected at least one file-read tool call, got " + toolNamesObserved);

      var failedToolEvents = events.stream().filter(e -> e instanceof QueryEvent.Error).toList();
      assertTrue(
          failedToolEvents.isEmpty(),
          () -> "no provider-level errors expected, got " + failedToolEvents);
    }
  }

  @Test
  void agentAcceptsImageAttachment(@TempDir Path tmp) throws Exception {
    var pngPath = tmp.resolve("color.png");
    writeSamplePng(pngPath);
    var options =
        SessionOptions.newBuilder().withModel(model).withSessionId("session-demo-att-test").build();

    var events = new CopyOnWriteArrayList<QueryEvent>();
    try (var session = AgentSession.create(options)) {
      session.events().subscribe(collector(events));
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
      assertTrue(!success.result().isBlank(), "expected the model to produce some text");
    }
  }

  @Test
  void agentMemoryWriteBlockedWithoutExplicitAllow(@TempDir Path tmp) throws Exception {
    seedFakeRepo(tmp);
    var ws = WorkspaceRoot.of(tmp);
    var memoryBackend = FileSystemMemoryBackend.of(ws);
    // No MemoryWrite allow rule — under DEFAULT mode this falls to ASK, which (until an
    // AskUserQuestion handler is wired into the permission system) blocks the call.
    var permission =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(PermissionRule.withGlob(PermissionEffect.ALLOW, "MemoryRead", "/memories/**")),
            List.of(),
            List.of());
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withPermission(permission)
            .withMemoryBackend(memoryBackend)
            .build();

    var events = new CopyOnWriteArrayList<QueryEvent>();
    try (var session = AgentSession.create(options)) {
      // Subscribe with an auto-denier — every QuestionAsked the permission system surfaces gets a
      // synthetic "Deny" answer so the loop unblocks. Without this, runBlocking would deadlock
      // waiting on session.answer.
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
                        ai.singlr.session.ask.AskUserQuestionResponse.single(
                            qa.request().questionId(), "Deny"));
                  }
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onComplete() {}
              });
      var prompt =
          "Please use MemoryWrite with op=create to save a note at /memories/test.md with content"
              + " 'hello world'. Just attempt it once.";
      session.runBlocking(UserMessage.text(prompt));
    }

    // Permission blocks happen BEFORE dispatch — the loop emits ToolBlocked directly without a
    // preceding ToolUse. So the right check is: no successful MemoryWrite ToolResult fired, and
    // nothing landed on disk.
    var successfulMemoryWrites =
        events.stream()
            .filter(e -> e instanceof QueryEvent.ToolResult)
            .map(e -> (QueryEvent.ToolResult) e)
            .filter(r -> r.call().name().equals(MemoryWriteTool.NAME))
            .filter(r -> r.result().success())
            .count();
    assertEquals(
        0L,
        successfulMemoryWrites,
        () -> "no MemoryWrite should have succeeded, got " + successfulMemoryWrites);
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

  private static Flow.Subscriber<QueryEvent> collector(List<QueryEvent> sink) {
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
        // ignore
      }

      @Override
      public void onComplete() {
        // ignore
      }
    };
  }
}
