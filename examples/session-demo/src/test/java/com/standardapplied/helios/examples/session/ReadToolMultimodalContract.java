/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.test.SampleDocuments;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>The model is forced to call Read on every turn, so each session ends at its two-turn limit
 * after the second request, the one carrying the attachment. Which path the model passes is its
 * choice: a run whose Read did not succeed skips, and {@code RecordedSessionTest} replays a
 * recorded session that reads and attaches both files. The fixtures are a 1x1 PNG and a one-page
 * "Hello, world!" PDF from {@link SampleDocuments}. Each subclass supplies its model and carries
 * its own API-key gate.
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
    var events = new CollectingSubscriber();
    try (var model = createModel(ToolChoice.required(ReadTool.NAME));
        var session =
            AgentSession.create(
                SessionOptions.newBuilder()
                    .withPreset(SessionPresets.readOnly(workspace))
                    .withModel(model)
                    .withLimits(SessionLimits.newBuilder().withMaxTurns(2).build())
                    .build())) {
      session.events().subscribe(events);
      var terminal = session.runBlocking(UserMessage.text("Read the file '" + file + "'."));
      events.awaitDone();

      assertTrue(
          terminal instanceof ResultMessage.Success
              || terminal instanceof ResultMessage.ErrorMaxTurns,
          () -> "ended as " + terminal);
      assertTrue(events.eventsOf(QueryEvent.Error.class).isEmpty());
      var reads = events.eventsOf(QueryEvent.ToolResult.class);
      assertFalse(reads.isEmpty());
      reads.forEach(read -> assertEquals(ReadTool.NAME, read.call().name()));
      assumeTrue(
          reads.getFirst().result().success(),
          () ->
              model.id()
                  + " passed Read a path that failed, "
                  + reads.getFirst().call().arguments()
                  + "; RecordedSessionTest#fileToolsRedactTheirOutputAndAttachWhatTheyRead"
                  + " covers the attachment");
    }
  }
}
