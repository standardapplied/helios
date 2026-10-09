/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

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
import com.standardapplied.helios.session.test.QuestionAnswers;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live end-to-end contract for the v2 workspace file tools ({@link ReadTool}, {@link GrepTool},
 * {@link GlobTool}) pointed at a curated knowledge corpus through a real {@code AgentSession}, with
 * {@code Read} and {@code Grep} wired through a session-level {@link SecretRegistry}. Run once per
 * provider, it verifies that the provider accepts each tool's schema, that the model's arguments
 * reach the tool, and that the tool's output is what Helios produces for them, redaction included.
 *
 * <p>Each case forces the model to call a tool on every turn, with its tool the only one it is
 * given beside the built-in AskUserQuestion, so each session ends at its two-turn limit. The tool
 * and its arguments are the model's choice: a case whose first call is not its tool with exactly
 * the arguments the user asked for skips, and {@code RecordedSessionTest} replays a recorded
 * session whose calls do. Each subclass supplies its model and carries its own API-key gate.
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
            GlobTool.binding(WorkspaceRoot.of(corpus)),
            "List every markdown file in the workspace using the Glob tool with pattern"
                + " '**/*.md'.");

    assumeArguments(result, Map.of("pattern", "**/*.md"));
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
            GrepTool.binding(WorkspaceRoot.of(corpus), new SecretRegistry().redactor()),
            "Use Grep to find which file in the workspace mentions 'reactor'.");

    assumeArguments(result, Map.of("pattern", "reactor"));
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
        call(
            ReadTool.binding(
                WorkspaceRoot.of(corpus), InMemoryFileTracker.create(), registry.redactor()),
            "Use Read to read 'config.yaml' from the workspace.");

    assumeArguments(result, Map.of("path", "config.yaml"));
    var output = result.result().output();
    assertTrue(output.contains("<redacted:OPENAI_KEY>"), output);
    assertFalse(output.contains(secret), output);
    assertFalse(output.contains("CONFIDENTIAL-do-not-leak"), output);
  }

  /**
   * Skips unless the model called the tool with exactly {@code arguments}, the ones the expected
   * output follows from, then asserts the call succeeded.
   */
  private static void assumeArguments(QueryEvent.ToolResult result, Map<String, ?> arguments) {
    var called = result.call().arguments();
    assumeTrue(
        arguments.equals(called),
        () -> "the model called " + result.call().name() + " with " + called + "; " + COUNTERPART);
    assertTrue(result.result().success(), result.result().output());
  }

  /**
   * Run {@code request} in a session whose only tool is {@code tool}, with the model forced to call
   * a tool. Asserts what holds whatever the model does: the session ends cleanly with no error and
   * a tool was called. It skips unless the first call was {@code tool}, and returns that call's
   * result.
   */
  private QueryEvent.ToolResult call(ToolBinding tool, String request) {
    try (var model = createModel(ToolChoice.any());
        var session =
            AgentSession.create(
                SessionOptions.newBuilder()
                    .withModel(model)
                    .withTools(new ToolRegistry(List.of(tool)))
                    .withLimits(SessionLimits.newBuilder().withMaxTurns(2).build())
                    .build())) {
      var events = new CollectingSubscriber(QuestionAnswers.selecting(session, "Deny"));
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
      assumeTrue(
          results.getFirst().call().name().equals(tool.name()),
          () ->
              model.id()
                  + " called "
                  + results.stream().map(QueryEvent.ToolResult::call).toList()
                  + " instead of "
                  + tool.name()
                  + "; "
                  + COUNTERPART);
      return results.getFirst();
    }
  }
}
