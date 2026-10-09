/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.SessionPresets;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.files.InMemoryFileTracker;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.test.QuestionAnswers;
import com.standardapplied.helios.session.test.SampleDocuments;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live end-to-end contract for {@link com.standardapplied.helios.session.files.ReadTool}'s
 * multimodal dispatch, run once per provider. {@code ReadToolTest} proves the bytes survive the
 * Read tool unchanged and {@code TurnRunnerToolDispatchTest} proves the attachment splice produces
 * the right {@code Message.user(text, inlineFiles)} shape; only a live call proves the provider
 * accepts the request that carries it: Read → loop splice → the provider's request builder turning
 * the {@code InlineFile} into its image / document block → the live server accepting the binary
 * payload.
 *
 * <p>The model is forced to call a tool on every turn, with Read the only one it is given beside
 * the built-in AskUserQuestion, so each session ends at its two-turn limit after the second
 * request, the one carrying the attachment. Which tool and path the model picks is its choice: a
 * run whose first turn attaches no file skips, since only a first-turn attachment reaches a
 * request, and {@code RecordedSessionTest} replays a recorded session that reads and attaches both
 * files. The fixtures are a 1x1 PNG and a one-page "Hello, world!" PDF from {@link
 * SampleDocuments}. Each subclass supplies its model and carries its own API-key gate.
 */
abstract class ReadToolMultimodalContract {

  /**
   * The live vision- and PDF-capable model under test, sending {@code toolChoice}; the caller
   * closes it.
   */
  protected abstract Model createModel(ToolChoice toolChoice);

  @Test
  void agentReadsRealPngAndTheProviderAcceptsIt(@TempDir Path workspace) throws IOException {
    Files.write(workspace.resolve("pixel.png"), SampleDocuments.pixelPng());

    readAndAttach(workspace, "pixel.png");
  }

  @Test
  void agentReadsRealPdfAndTheProviderAcceptsIt(@TempDir Path workspace) throws IOException {
    Files.write(workspace.resolve("greeting.pdf"), SampleDocuments.helloWorldPdf());

    readAndAttach(workspace, "greeting.pdf");
  }

  private void readAndAttach(Path workspace, String file) {
    var read = ReadTool.binding(WorkspaceRoot.of(workspace), InMemoryFileTracker.create());
    try (var model = createModel(ToolChoice.any());
        var session =
            AgentSession.create(
                SessionOptions.newBuilder()
                    .withPreset(SessionPresets.readOnly(workspace))
                    .withTools(new ToolRegistry(List.of(read)))
                    .withModel(model)
                    .withLimits(SessionLimits.newBuilder().withMaxTurns(2).build())
                    .build())) {
      var events = new CollectingSubscriber(QuestionAnswers.selecting(session, "Deny"));
      session.events().subscribe(events);
      var terminal = session.runBlocking(UserMessage.text("Read the file '" + file + "'."));
      events.awaitDone();

      assertTrue(
          terminal instanceof ResultMessage.Success
              || terminal instanceof ResultMessage.ErrorMaxTurns,
          () -> "ended as " + terminal);
      assertTrue(events.eventsOf(QueryEvent.Error.class).isEmpty());
      var results = events.eventsOf(QueryEvent.ToolResult.class);
      assertFalse(results.isEmpty());
      assumeTrue(
          results.stream()
              .anyMatch(
                  result ->
                      result.turnIndex() == 1
                          && result.call().name().equals(ReadTool.NAME)
                          && result.result().success()
                          && result.result().hasAttachments()),
          () ->
              model.id()
                  + " attached no file on its first turn, "
                  + results.stream().map(QueryEvent.ToolResult::call).toList()
                  + "; RecordedSessionTest#fileToolsRedactTheirOutputAndAttachWhatTheyRead"
                  + " covers the attachment");
    }
  }
}
