/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.files.GlobTool;
import com.standardapplied.helios.session.files.GrepTool;
import com.standardapplied.helios.session.files.InMemoryFileTracker;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live end-to-end contract for the v2 workspace file tools ({@link ReadTool}, {@link GrepTool},
 * {@link GlobTool}) pointed at a curated knowledge corpus through a real {@code AgentSession}, with
 * {@code Read} and {@code Grep} wired through a session-level {@link SecretRegistry}. Run once per
 * provider, it verifies tool-schema discovery, argument round-tripping and end-to-end secret
 * redaction, and catches cross-provider divergence in how tool calls are encoded on the wire.
 *
 * <ul>
 *   <li>{@link #agentDiscoversFilesViaGlob} — model uses {@code Glob} to list markdown files.
 *   <li>{@link #agentFindsPatternViaGrep} — model uses {@code Grep} to locate "reactor" in the
 *       corpus.
 *   <li>{@link #readRedactsRegisteredSecretsEndToEnd} — the load-bearing one: registered secret in
 *       a config file, model is asked to read it, assistant's final reply MUST NOT contain the raw
 *       secret bytes. Proves the {@code Redactor} overload survives the full tool-result-to-model
 *       round-trip.
 * </ul>
 *
 * <p>Each subclass supplies its model and carries its own API-key gate.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class WorkspaceReadOnlyRedactionContract {

  private Model model;

  /** The live tool-calling model under test, closed after the class's tests. */
  protected abstract Model createModel();

  @BeforeAll
  void openModel() {
    model = createModel();
  }

  @AfterAll
  void closeModel() throws Exception {
    if (model != null) {
      model.close();
    }
  }

  @Test
  void agentDiscoversFilesViaGlob(@TempDir Path corpus) throws IOException {
    Files.writeString(corpus.resolve("intro.md"), "# Intro\n", StandardCharsets.UTF_8);
    Files.writeString(corpus.resolve("guide.md"), "# Guide\n", StandardCharsets.UTF_8);
    Files.writeString(corpus.resolve("config.yaml"), "key: value\n", StandardCharsets.UTF_8);

    var text =
        ask(
            corpus,
            new SecretRegistry(),
            "List every markdown file in the workspace using the Glob tool with pattern"
                + " '**/*.md'. Return the bare list of names.");
    assertTrue(
        text.contains("intro.md") && text.contains("guide.md"),
        () -> "assistant must name both markdown files via Glob: " + text);
    assertFalse(
        text.contains("config.yaml"), () -> "Glob with '**/*.md' must not surface yaml: " + text);
  }

  @Test
  void agentFindsPatternViaGrep(@TempDir Path corpus) throws IOException {
    Files.writeString(
        corpus.resolve("patterns.md"),
        "# Architecture notes\nThe reactor pattern decouples producers and consumers.\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        corpus.resolve("misc.md"), "# Misc\nNothing relevant here.\n", StandardCharsets.UTF_8);

    var text =
        ask(
            corpus,
            new SecretRegistry(),
            "Use Grep to find which file in the workspace mentions 'reactor'."
                + " Tell me only the filename.");
    assertTrue(
        text.contains("patterns.md"),
        () -> "assistant must identify the file containing the reactor term: " + text);
  }

  @Test
  void readRedactsRegisteredSecretsEndToEnd(@TempDir Path corpus) throws IOException {
    // Read's text-body output passes through the registry's Redactor before it becomes the tool
    // result, so the model never sees the raw secret bytes and cannot echo them back.
    var secret = "sk-test-CONFIDENTIAL-do-not-leak-789012";
    var registry = new SecretRegistry();
    registry.register("OPENAI_KEY", secret);
    Files.writeString(
        corpus.resolve("config.yaml"),
        "service: backend\napi_key: " + secret + "\nregion: us-east-1\n",
        StandardCharsets.UTF_8);

    var text =
        ask(
            corpus,
            registry,
            "Use Read to read 'config.yaml' from the workspace. Quote the entire"
                + " api_key value back to me exactly as it appears in the file.");
    assertFalse(
        text.contains(secret),
        () ->
            "Registered secret bytes MUST NOT appear in the assistant's reply — Redactor wiring on"
                + " ReadTool is the provider-agnostic contract. Got: "
                + text);
    assertFalse(
        text.contains("CONFIDENTIAL-do-not-leak"),
        () -> "Even a substring of the registered secret must be scrubbed: " + text);
    assertTrue(
        text.toLowerCase(Locale.ROOT).contains("redact") || text.contains("OPENAI_KEY"),
        () ->
            "Assistant should reference the redaction marker so a downstream auditor can"
                + " see the secret was elided, not silently dropped. Got: "
                + text);
  }

  /**
   * Run {@code request} in a session bound to {@code corpus} with only the v2 file tools — Read and
   * Grep wired through {@code registry}'s redactor; Glob takes no redactor by design (paths are
   * structural, not secret material) — and return the assistant's final reply.
   */
  private String ask(Path corpus, SecretRegistry registry, String request) {
    var workspace = WorkspaceRoot.of(corpus);
    var tracker = InMemoryFileTracker.create();
    var redactor = registry.redactor();
    var bindings =
        List.of(
            ReadTool.binding(workspace, tracker, redactor),
            GrepTool.binding(workspace, redactor),
            GlobTool.binding(workspace));
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withTools(new ToolRegistry(bindings))
            .withSystemPrompt(
                "You are a precise knowledge-base assistant. Always call the Read / Grep / Glob"
                    + " tools rather than answering from memory. Be terse — one sentence answers"
                    + " when the user asks for a fact.")
            .withLimits(SessionLimits.newBuilder().withMaxTurns(4).build())
            .build();
    try (var session = AgentSession.create(options)) {
      var terminal = session.runBlocking(UserMessage.text(request));
      return assertInstanceOf(
              ResultMessage.Success.class,
              terminal,
              () -> "expected Success terminal, got " + terminal)
          .result();
    }
  }
}
