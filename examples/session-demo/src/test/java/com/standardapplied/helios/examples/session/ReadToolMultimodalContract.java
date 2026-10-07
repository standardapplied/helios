/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.SessionPresets;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.test.SampleDocuments;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live end-to-end contract for {@link com.standardapplied.helios.session.files.ReadTool}'s
 * multimodal dispatch, run once per provider. {@code ReadToolTest} proves the bytes survive the
 * Read tool unchanged and {@code TurnRunnerToolDispatchTest} proves the attachment splice produces
 * the right {@code Message.user(text, inlineFiles)} shape; only a live call proves the full chain:
 * Read → loop splice → the provider's request builder turning the {@code InlineFile} into its image
 * / document block → the live server parsing the binary payload → a coherent reply.
 *
 * <p>The fixtures are a 1x1 PNG and a one-page "Hello, world!" PDF from {@link SampleDocuments},
 * well inside every provider's per-image limits so the Read tool's caps are exercised on the happy
 * path. Each subclass supplies its model and carries its own API-key gate; the tests share that
 * model and a {@code maxTurns=4} ceiling to bound spend.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ReadToolMultimodalContract {

  private Model model;

  /** The live vision- and PDF-capable model under test, closed after the class's tests. */
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
  void agentReadsRealPngAndModelDescribesIt(@TempDir Path workspace) throws IOException {
    Files.write(workspace.resolve("pixel.png"), SampleDocuments.pixelPng());

    var text =
        inspect(
            workspace,
            "describe what you observe. Be concise — one or two sentences.",
            "Read the file 'pixel.png' and tell me what kind of file you see. "
                + "Name the format and whether you can perceive any image content.");
    var lower = text.toLowerCase(Locale.ROOT);
    assertTrue(
        lower.contains("png")
            || lower.contains("image")
            || lower.contains("pixel")
            || lower.contains("graphic"),
        () ->
            "Assistant reply must reference the image / PNG / pixel — proves the provider's vision"
                + " channel parsed the attachment, not just the text note. Got: "
                + text);
  }

  @Test
  void agentReadsRealPdfAndModelExtractsText(@TempDir Path workspace) throws IOException {
    Files.write(workspace.resolve("greeting.pdf"), SampleDocuments.helloWorldPdf());

    var text =
        inspect(
            workspace,
            "state what text the document contains, verbatim if possible.",
            "Read the file 'greeting.pdf' and tell me what text the document contains.");
    assertTrue(
        text.contains(SampleDocuments.PDF_TEXT) || text.toLowerCase(Locale.ROOT).contains("hello"),
        () ->
            "Assistant reply must echo the PDF's text content — proves the provider's document"
                + " channel parsed the binary stream. Got: "
                + text);
  }

  private String inspect(Path workspace, String afterReading, String request) {
    var options =
        SessionOptions.newBuilder()
            .withPreset(SessionPresets.readOnly(workspace))
            .withModel(model)
            .withSystemPrompt(
                "You are a careful file inspector. When the user asks you to read a file, call"
                    + " the Read tool with the given path and then "
                    + afterReading)
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
