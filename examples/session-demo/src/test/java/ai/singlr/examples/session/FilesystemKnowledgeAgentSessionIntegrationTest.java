/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */
package ai.singlr.examples.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.SecretRegistry;
import ai.singlr.core.knowledge.FilesystemKnowledge;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.gemini.GeminiModelId;
import ai.singlr.gemini.GeminiProvider;
import ai.singlr.session.AgentSession;
import ai.singlr.session.ResultMessage;
import ai.singlr.session.SessionLimits;
import ai.singlr.session.SessionOptions;
import ai.singlr.session.UserMessage;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolCategory;
import ai.singlr.session.tools.ToolRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end live regression for {@link FilesystemKnowledge}'s {@code kb_grep} / {@code kb_glob} /
 * {@code kb_read} tools through a real agent session against live Gemini. The direct-invocation
 * unit tests in {@code core/test/.../FilesystemKnowledgeTest} prove each tool works in isolation;
 * this file proves they work when an LLM is the one calling them — schema discovery, argument
 * encoding, tool dispatch, output handling, and end-to-end secret redaction all chained together.
 *
 * <p>Three scenarios, each ~5-8 s on a warm key:
 *
 * <ul>
 *   <li>{@link #agentDiscoversFilesViaKbGlob} — model uses {@code kb_glob} to list markdown files.
 *   <li>{@link #agentFindsPatternViaKbGrep} — model uses {@code kb_grep} to locate "reactor" in the
 *       corpus.
 *   <li>{@link #kbReadRedactsRegisteredSecretsEndToEnd} — the load-bearing one: registered secret
 *       in a config file, model is asked to read it, assistant's final reply MUST NOT contain the
 *       raw secret bytes. Proves {@code FilesystemKnowledge}'s redaction survives the full
 *       tool-result-to-model round-trip, not just the unit-test direct invocation.
 * </ul>
 *
 * <p>Guarded by {@code GEMINI_API_KEY} so the suite stays runnable offline. Same {@code maxTurns=4}
 * budget as the multimodal peers.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
final class FilesystemKnowledgeAgentSessionIntegrationTest {

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
  void agentDiscoversFilesViaKbGlob(@TempDir Path corpus) throws IOException {
    Files.writeString(corpus.resolve("intro.md"), "# Intro\n", StandardCharsets.UTF_8);
    Files.writeString(corpus.resolve("guide.md"), "# Guide\n", StandardCharsets.UTF_8);
    Files.writeString(corpus.resolve("config.yaml"), "key: value\n", StandardCharsets.UTF_8);

    try (var session = sessionWithKnowledgeBase(corpus, new SecretRegistry())) {
      var terminal =
          session.runBlocking(
              UserMessage.text(
                  "List every markdown file in the knowledge base using the kb_glob tool with"
                      + " pattern '**/*.md'. Return the bare list of names."));
      var text = assertSuccessText(terminal);
      assertTrue(
          text.contains("intro.md") && text.contains("guide.md"),
          () -> "assistant must name both markdown files via kb_glob: " + text);
      assertFalse(
          text.contains("config.yaml"),
          () -> "kb_glob with '**/*.md' must not surface yaml: " + text);
    }
  }

  @Test
  void agentFindsPatternViaKbGrep(@TempDir Path corpus) throws IOException {
    Files.writeString(
        corpus.resolve("patterns.md"),
        "# Architecture notes\nThe reactor pattern decouples producers and consumers.\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        corpus.resolve("misc.md"), "# Misc\nNothing relevant here.\n", StandardCharsets.UTF_8);

    try (var session = sessionWithKnowledgeBase(corpus, new SecretRegistry())) {
      var terminal =
          session.runBlocking(
              UserMessage.text(
                  "Use kb_grep to find which file in the knowledge base mentions 'reactor'."
                      + " Tell me only the filename."));
      var text = assertSuccessText(terminal);
      assertTrue(
          text.contains("patterns.md"),
          () -> "assistant must identify the file containing the reactor term: " + text);
    }
  }

  @Test
  void kbReadRedactsRegisteredSecretsEndToEnd(@TempDir Path corpus) throws IOException {
    // The load-bearing test: prove FilesystemKnowledge's byte-level redaction survives all the
    // way to the model's response, not just to the tool output. The model never sees the raw
    // secret bytes because kb_read's success() wraps every output through the SecretRegistry's
    // Redactor — so the model literally cannot echo the secret back.
    var secret = "sk-test-CONFIDENTIAL-do-not-leak-789012";
    var registry = new SecretRegistry();
    registry.register("OPENAI_KEY", secret);
    Files.writeString(
        corpus.resolve("config.yaml"),
        "service: backend\napi_key: " + secret + "\nregion: us-east-1\n",
        StandardCharsets.UTF_8);

    try (var session = sessionWithKnowledgeBase(corpus, registry)) {
      var terminal =
          session.runBlocking(
              UserMessage.text(
                  "Use kb_read to read 'config.yaml' from the knowledge base. Quote the entire"
                      + " api_key value back to me exactly as it appears in the file."));
      var text = assertSuccessText(terminal);
      assertFalse(
          text.contains(secret),
          () ->
              "Registered secret bytes MUST NOT appear in the assistant's reply — "
                  + "FilesystemKnowledge redaction is the contract. Got: "
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
   * Build a session bound to the given knowledge corpus and secret registry. Wires the three {@code
   * kb_*} tools as the only tools available; the agent must use them or fail.
   */
  private static AgentSession sessionWithKnowledgeBase(Path corpus, SecretRegistry registry) {
    var kb = FilesystemKnowledge.builder(corpus).withSecretRegistry(registry).build();
    var bindings =
        kb.tools().stream()
            .map(t -> ToolBinding.newBuilder(t).withCategory(ToolCategory.READ).build())
            .toList();
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withTools(new ToolRegistry(bindings))
            .withSystemPrompt(
                "You are a precise knowledge-base assistant. Always call the kb_* tools rather"
                    + " than answering from memory. Be terse — one sentence answers when the"
                    + " user asks for a fact.")
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
