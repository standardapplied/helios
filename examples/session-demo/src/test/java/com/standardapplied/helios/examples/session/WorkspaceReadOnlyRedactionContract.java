/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.files.GlobTool;
import com.standardapplied.helios.session.files.GrepTool;
import com.standardapplied.helios.session.files.InMemoryFileTracker;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live end-to-end contract for the v2 workspace file tools ({@link ReadTool}, {@link GrepTool},
 * {@link GlobTool}) pointed at a curated knowledge corpus through a real {@code AgentSession}, with
 * {@code Read} and {@code Grep} wired through a session-level {@link SecretRegistry}. Run once per
 * provider, it verifies that the provider accepts each tool's schema, that the model's arguments
 * reach the tool, and that the tool's output is what Helios produces for them, redaction included.
 *
 * <p>Each case forces the model to call its tool on every turn, so each session ends at its
 * two-turn limit. The arguments are the model's choice: a case whose call does not name what the
 * user asked for skips, and {@code RecordedSessionTest} replays a recorded session whose calls do.
 * Each subclass supplies its model and carries its own API-key gate.
 */
abstract class WorkspaceReadOnlyRedactionContract {

  private static final String COUNTERPART =
      "RecordedSessionTest#fileToolsRedactTheirOutputAndAttachWhatTheyRead covers the output";

  /** The live tool-calling model under test, sending {@code toolChoice}; the caller closes it. */
  protected abstract Model createModel(ToolChoice toolChoice);

  @Test
  void agentDiscoversFilesViaGlob(@TempDir Path corpus) throws IOException {
    Files.writeString(corpus.resolve("intro.md"), "# Intro\n", StandardCharsets.UTF_8);
    Files.writeString(corpus.resolve("guide.md"), "# Guide\n", StandardCharsets.UTF_8);
    Files.writeString(corpus.resolve("config.yaml"), "key: value\n", StandardCharsets.UTF_8);

    var result =
        call(
            corpus,
            new SecretRegistry(),
            GlobTool.NAME,
            "List every markdown file in the workspace using the Glob tool with pattern"
                + " '**/*.md'.");

    var pattern = String.valueOf(result.call().arguments().get("pattern"));
    assumeTrue(
        pattern.endsWith("*.md") && result.result().success(),
        () -> "the model globbed " + pattern + "; " + COUNTERPART);
    var output = result.result().output();
    assertTrue(output.contains("intro.md") && output.contains("guide.md"), output);
    assertFalse(output.contains("config.yaml"), output);
  }

  @Test
  void agentFindsPatternViaGrep(@TempDir Path corpus) throws IOException {
    Files.writeString(
        corpus.resolve("patterns.md"),
        "# Architecture notes\nThe reactor pattern decouples producers and consumers.\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        corpus.resolve("misc.md"), "# Misc\nNothing relevant here.\n", StandardCharsets.UTF_8);

    var result =
        call(
            corpus,
            new SecretRegistry(),
            GrepTool.NAME,
            "Use Grep to find which file in the workspace mentions 'reactor'.");

    var pattern = String.valueOf(result.call().arguments().get("pattern"));
    assumeTrue(
        "reactor".equals(pattern) && result.result().success(),
        () -> "the model grepped for " + pattern + "; " + COUNTERPART);
    var output = result.result().output();
    assertTrue(output.contains("patterns.md"), output);
    assertFalse(output.contains("misc.md"), output);
  }

  @Test
  void readRedactsRegisteredSecretsEndToEnd(@TempDir Path corpus) throws IOException {
    var secret = "sk-test-CONFIDENTIAL-do-not-leak-789012";
    var registry = new SecretRegistry();
    registry.register("OPENAI_KEY", secret);
    Files.writeString(
        corpus.resolve("config.yaml"),
        "service: backend\napi_key: " + secret + "\nregion: us-east-1\n",
        StandardCharsets.UTF_8);

    var result =
        call(corpus, registry, ReadTool.NAME, "Use Read to read 'config.yaml' from the workspace.");

    var path = String.valueOf(result.call().arguments().get("path"));
    assumeTrue(
        path.endsWith("config.yaml") && result.result().success(),
        () -> "the model read " + path + "; " + COUNTERPART);
    var output = result.result().output();
    assertTrue(output.contains("<redacted:OPENAI_KEY>"), output);
    assertFalse(output.contains(secret), output);
    assertFalse(output.contains("CONFIDENTIAL-do-not-leak"), output);
  }

  /**
   * Run {@code request} in a session bound to {@code corpus} with only the v2 file tools — Read and
   * Grep wired through {@code registry}'s redactor; Glob takes no redactor by design (paths are
   * structural, not secret material) — and the model forced to call {@code tool}. Asserts what
   * holds whatever the model does: the session ends cleanly with no error and called {@code tool},
   * and returns the first result of that call.
   */
  private QueryEvent.ToolResult call(
      Path corpus, SecretRegistry registry, String tool, String request) {
    var workspace = WorkspaceRoot.of(corpus);
    var tracker = InMemoryFileTracker.create();
    var redactor = registry.redactor();
    var bindings =
        List.of(
            ReadTool.binding(workspace, tracker, redactor),
            GrepTool.binding(workspace, redactor),
            GlobTool.binding(workspace));
    var events = new CollectingSubscriber();
    try (var model = createModel(ToolChoice.required(tool));
        var session =
            AgentSession.create(
                SessionOptions.newBuilder()
                    .withModel(model)
                    .withTools(new ToolRegistry(bindings))
                    .withLimits(SessionLimits.newBuilder().withMaxTurns(2).build())
                    .build())) {
      session.events().subscribe(events);
      var terminal = session.runBlocking(UserMessage.text(request));
      events.awaitDone();

      assertTrue(
          terminal instanceof ResultMessage.Success
              || terminal instanceof ResultMessage.ErrorMaxTurns,
          () -> "ended as " + terminal);
      assertTrue(events.eventsOf(QueryEvent.Error.class).isEmpty());
      var results = events.eventsOf(QueryEvent.ToolResult.class);
      assertFalse(results.isEmpty());
      results.forEach(result -> assertEquals(tool, result.call().name()));
      return results.getFirst();
    }
  }
}
