/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */
package ai.singlr.examples.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.SecretRegistry;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.gemini.GeminiModelId;
import ai.singlr.gemini.GeminiProvider;
import ai.singlr.session.AgentSession;
import ai.singlr.session.ResultMessage;
import ai.singlr.session.SessionLimits;
import ai.singlr.session.SessionOptions;
import ai.singlr.session.UserMessage;
import ai.singlr.session.files.GlobTool;
import ai.singlr.session.files.GrepTool;
import ai.singlr.session.files.InMemoryFileTracker;
import ai.singlr.session.files.ReadTool;
import ai.singlr.session.files.WorkspaceRoot;
import ai.singlr.session.tools.ToolRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end live regression for the v2 workspace file tools ({@link ReadTool}, {@link GrepTool},
 * {@link GlobTool}) pointed at a curated knowledge corpus, with {@code Read} and {@code Grep} wired
 * through a session-level {@link SecretRegistry}. Replaces the v1 {@code FilesystemKnowledge}
 * integration test that proved the same end-to-end contract through the now-removed {@code kb_*}
 * tools — same coverage, one set of file tools instead of two.
 *
 * <p>Three scenarios, each ~5-8 s on a warm key:
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
 * <p>Guarded by {@code GEMINI_API_KEY} so the suite stays runnable offline.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
final class WorkspaceReadOnlyRedactionAgentSessionIntegrationTest {

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
  void agentDiscoversFilesViaGlob(@TempDir Path corpus) throws IOException {
    Files.writeString(corpus.resolve("intro.md"), "# Intro\n", StandardCharsets.UTF_8);
    Files.writeString(corpus.resolve("guide.md"), "# Guide\n", StandardCharsets.UTF_8);
    Files.writeString(corpus.resolve("config.yaml"), "key: value\n", StandardCharsets.UTF_8);

    try (var session = sessionWithCorpus(corpus, new SecretRegistry())) {
      var terminal =
          session.runBlocking(
              UserMessage.text(
                  "List every markdown file in the workspace using the Glob tool with pattern"
                      + " '**/*.md'. Return the bare list of names."));
      var text = assertSuccessText(terminal);
      assertTrue(
          text.contains("intro.md") && text.contains("guide.md"),
          () -> "assistant must name both markdown files via Glob: " + text);
      assertFalse(
          text.contains("config.yaml"), () -> "Glob with '**/*.md' must not surface yaml: " + text);
    }
  }

  @Test
  void agentFindsPatternViaGrep(@TempDir Path corpus) throws IOException {
    Files.writeString(
        corpus.resolve("patterns.md"),
        "# Architecture notes\nThe reactor pattern decouples producers and consumers.\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        corpus.resolve("misc.md"), "# Misc\nNothing relevant here.\n", StandardCharsets.UTF_8);

    try (var session = sessionWithCorpus(corpus, new SecretRegistry())) {
      var terminal =
          session.runBlocking(
              UserMessage.text(
                  "Use Grep to find which file in the workspace mentions 'reactor'."
                      + " Tell me only the filename."));
      var text = assertSuccessText(terminal);
      assertTrue(
          text.contains("patterns.md"),
          () -> "assistant must identify the file containing the reactor term: " + text);
    }
  }

  @Test
  void readRedactsRegisteredSecretsEndToEnd(@TempDir Path corpus) throws IOException {
    // Load-bearing: prove the Redactor overload on ReadTool survives the full round-trip to the
    // model's reply. The model never sees the raw secret bytes because Read's text-body output is
    // piped through the SecretRegistry's Redactor before becoming the tool result — so the model
    // literally cannot echo the secret back.
    var secret = "sk-test-CONFIDENTIAL-do-not-leak-789012";
    var registry = new SecretRegistry();
    registry.register("OPENAI_KEY", secret);
    Files.writeString(
        corpus.resolve("config.yaml"),
        "service: backend\napi_key: " + secret + "\nregion: us-east-1\n",
        StandardCharsets.UTF_8);

    try (var session = sessionWithCorpus(corpus, registry)) {
      var terminal =
          session.runBlocking(
              UserMessage.text(
                  "Use Read to read 'config.yaml' from the workspace. Quote the entire"
                      + " api_key value back to me exactly as it appears in the file."));
      var text = assertSuccessText(terminal);
      assertFalse(
          text.contains(secret),
          () ->
              "Registered secret bytes MUST NOT appear in the assistant's reply — "
                  + "Redactor wiring on ReadTool is the contract. Got: "
                  + text);
      assertFalse(
          text.contains("CONFIDENTIAL-do-not-leak"),
          () -> "Even a substring of the registered secret must be scrubbed: " + text);
      assertTrue(
          text.toLowerCase(java.util.Locale.ROOT).contains("redact") || text.contains("OPENAI_KEY"),
          () ->
              "Assistant should reference the redaction marker so a downstream auditor can"
                  + " see the secret was elided, not silently dropped. Got: "
                  + text);
    }
  }

  /**
   * Build a session bound to the given corpus with the v2 file tools — Read and Grep both wired
   * through the supplied registry's redactor; Glob takes no redactor by design (paths are
   * structural, not secret material). The agent must use these tools or fail; no other tools.
   */
  private static AgentSession sessionWithCorpus(Path corpus, SecretRegistry registry) {
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
    return AgentSession.create(options);
  }

  private static String assertSuccessText(ResultMessage terminal) {
    assertNotNull(terminal);
    if (!(terminal instanceof ResultMessage.Success success)) {
      throw new AssertionError(
          "expected Success terminal, got "
              + terminal.getClass().getSimpleName()
              + ": "
              + terminal);
    }
    return success.result();
  }
}
