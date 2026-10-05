/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.gemini.GeminiModelId;
import com.standardapplied.helios.gemini.GeminiProvider;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.ConsoleEventPrinter;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.files.GlobTool;
import com.standardapplied.helios.session.files.GrepTool;
import com.standardapplied.helios.session.files.InMemoryFileTracker;
import com.standardapplied.helios.session.files.LsTool;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.memory.FileSystemMemoryBackend;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * End-to-end demo of the Helios session SDK against the Gemini API.
 *
 * <p>Two scenarios:
 *
 * <ol>
 *   <li><b>Explore + remember</b> — seeds a tiny fake repo, points the agent at it with file tools
 *       and a memory backend, and asks the model to write a one-line summary into {@code
 *       /memories/project/summary.md}. Exercises Read / LS / Glob / Grep, MemoryRead, MemoryWrite,
 *       the permission system, and the event stream.
 *   <li><b>Attachment</b> — sends a tiny generated PNG as a {@link UserMessage} attachment so the
 *       provider receives an {@code image} content part natively.
 * </ol>
 *
 * <p>Run with {@code mvn exec:java -pl examples/session-demo} after setting {@code GEMINI_API_KEY}.
 */
public final class SessionDemoMain {

  private SessionDemoMain() {}

  public static void main(String[] args) throws Exception {
    var apiKey = System.getenv("GEMINI_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      System.err.println("Set GEMINI_API_KEY to run this demo.");
      System.exit(1);
      return;
    }

    var model =
        new GeminiProvider()
            .create(
                GeminiModelId.GEMINI_3_5_FLASH.id(),
                ModelConfig.newBuilder().withApiKey(apiKey).build());

    try {
      runExploreAndRememberScenario(model);
      runAttachmentScenario(model);
    } finally {
      model.close();
    }
  }

  private static void runExploreAndRememberScenario(Model model) throws IOException {
    System.out.println("\n=== Scenario 1: explore + remember ===");
    var workspace = createFakeRepo();
    System.out.println("workspace: " + workspace);

    var options = exploreAndRememberOptions(model, workspace).build();

    try (var session = AgentSession.create(options)) {
      session.events().subscribe(new ConsoleEventPrinter(System.out));
      var prompt =
          "You're looking at a tiny sample repo. Use the file tools to see what's in it (LS, Glob,"
              + " Grep, Read are available). Then write a one-line summary to"
              + " /memories/project/summary.md describing what the repo contains. Use MemoryWrite"
              + " with op=create. Keep it brief.";
      var result = session.runBlocking(UserMessage.text(prompt));
      printTerminal(result);
    }

    var summaryPath = workspace.resolve(".agent/memory/project/summary.md");
    if (Files.exists(summaryPath)) {
      System.out.println("\nfinal memory content:");
      System.out.println(Files.readString(summaryPath, StandardCharsets.UTF_8));
    } else {
      System.out.println("\n(no summary.md was written — model may have chosen a different path)");
    }
  }

  /**
   * The explore-and-remember session: the read-only file tools over {@code workspace}, memory under
   * it, and memory reads and writes allowed without asking.
   *
   * @param model the model the session talks to
   * @param workspace the repository to explore
   * @return the options, ready for further limits before {@code build()}
   */
  static SessionOptions.Builder exploreAndRememberOptions(Model model, Path workspace) {
    var ws = WorkspaceRoot.of(workspace);
    var tools =
        new ToolRegistry(
            List.of(
                ReadTool.binding(ws, InMemoryFileTracker.create()),
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
    return SessionOptions.newBuilder()
        .withModel(model)
        .withTools(tools)
        .withPermission(permission)
        .withMemoryBackend(FileSystemMemoryBackend.of(ws));
  }

  private static void runAttachmentScenario(Model model) throws IOException {
    System.out.println("\n=== Scenario 2: attachment ===");
    var tmp = Files.createTempDirectory("helios-session-demo-att-");
    var pngPath = tmp.resolve("color.png");
    writeSamplePng(pngPath);
    System.out.println("attached: " + pngPath);

    var options =
        SessionOptions.newBuilder().withModel(model).withSessionId("session-demo-att").build();

    try (var session = AgentSession.create(options)) {
      session.events().subscribe(new ConsoleEventPrinter(System.out));
      var msg =
          UserMessage.newBuilder()
              .withText(
                  "I'm sending you a small generated image. Briefly describe what you can tell"
                      + " about it in one sentence.")
              .withAttachment(pngPath)
              .build();
      var result = session.runBlocking(msg);
      printTerminal(result);
    }
  }

  private static Path createFakeRepo() throws IOException {
    var tmp = Files.createTempDirectory("helios-session-demo-");
    Files.writeString(
        tmp.resolve("README.md"),
        "# Helios Demo Repo\n\nA tiny three-file repo for the session SDK demo.\n",
        StandardCharsets.UTF_8);
    Files.createDirectories(tmp.resolve("src/main/java/demo"));
    Files.writeString(
        tmp.resolve("src/main/java/demo/Main.java"),
        """
        package demo;

        /** Entry point. */
        public final class Main {
          public static void main(String[] args) {
            System.out.println("hello from helios demo");
          }
        }
        """,
        StandardCharsets.UTF_8);
    Files.writeString(
        tmp.resolve("notes.md"),
        "Project status: experimental\nOwner: nobody\n",
        StandardCharsets.UTF_8);
    return tmp;
  }

  private static void writeSamplePng(Path target) throws IOException {
    var img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
    for (var y = 0; y < 16; y++) {
      for (var x = 0; x < 16; x++) {
        var r = (x * 16) & 0xFF;
        var g = (y * 16) & 0xFF;
        var b = ((x + y) * 8) & 0xFF;
        img.setRGB(x, y, (r << 16) | (g << 8) | b);
      }
    }
    ImageIO.write(img, "PNG", target.toFile());
  }

  private static void printTerminal(ResultMessage result) {
    System.out.println();
    System.out.println("--- terminal ---");
    System.out.println("status:  " + result.getClass().getSimpleName());
    if (result instanceof ResultMessage.Success s) {
      System.out.println("text:    " + truncate(s.result()));
    }
    System.out.println(
        "usage:   in="
            + result.usage().inputTokens()
            + " out="
            + result.usage().outputTokens()
            + " total="
            + result.usage().totalTokens());
    System.out.println("elapsed: " + result.duration());
  }

  private static String truncate(String s) {
    if (s == null) {
      return "(null)";
    }
    if (s.length() > 400) {
      return s.substring(0, 400) + "... [" + s.length() + " chars total]";
    }
    return s;
  }
}
