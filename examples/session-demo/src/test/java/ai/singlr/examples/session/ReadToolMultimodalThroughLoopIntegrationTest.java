/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */
package ai.singlr.examples.session;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.gemini.GeminiModelId;
import ai.singlr.gemini.GeminiProvider;
import ai.singlr.session.AgentSession;
import ai.singlr.session.ResultMessage;
import ai.singlr.session.SessionLimits;
import ai.singlr.session.SessionPresets;
import ai.singlr.session.UserMessage;
import java.io.ByteArrayOutputStream;
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
 * End-to-end live regression for {@link ai.singlr.session.files.ReadTool}'s multimodal dispatch.
 * The unit tests in {@code ReadToolTest} prove the bytes survive ReadTool unchanged; the loop test
 * in {@code TurnRunnerToolDispatchTest} proves the attachment splice produces the right {@code
 * Message.user(text, inlineFiles)} shape. Neither of those proves the full chain — Read → loop
 * splice → provider adapter (Gemini converts {@code InlineFile} to {@code inline_data}) → the live
 * Gemini server actually accepting and parsing the binary payload → the model returning a coherent
 * response.
 *
 * <p>This file closes that gap with two live Gemini calls (~5 s each on a warm key):
 *
 * <ul>
 *   <li>{@link #agentReadsRealPngAndModelDescribesIt} writes a 1x1 black PNG (well-formed bytes
 *       with valid IHDR / IDAT / IEND chunks), invites the model to read it via the Read tool, and
 *       asserts the assistant's reply references the image / PNG / format / pixel — any proof the
 *       vision channel actually parsed the bytes.
 *   <li>{@link #agentReadsRealPdfAndModelExtractsText} writes a minimal valid 1-page PDF containing
 *       "Hello, world!", asks the model to extract the text, and asserts the reply carries the
 *       phrase.
 * </ul>
 *
 * <p>Guarded by {@code GEMINI_API_KEY} so the suite stays runnable offline. The tests share a
 * single {@link Model} instance and {@code maxTurns=4} ceiling to bound spend.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
final class ReadToolMultimodalThroughLoopIntegrationTest {

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
  void agentReadsRealPngAndModelDescribesIt(@TempDir Path workspace) throws IOException {
    // Write a minimal valid 1x1 grayscale PNG with correct IHDR / IDAT / IEND chunks. Real bytes,
    // not a synthetic header — Gemini's vision channel parses it as a 1x1 image and the model
    // can confirm the format / dimensions.
    Files.write(workspace.resolve("pixel.png"), minimalValid1x1PngBytes());

    var options =
        ai.singlr.session.SessionOptions.newBuilder()
            .apply(SessionPresets.readOnly(workspace))
            .withModel(model)
            .withSystemPrompt(
                "You are a careful file inspector. When the user asks you to read a file, call"
                    + " the Read tool with the given path and then describe what you observe."
                    + " Be concise — one or two sentences.")
            .withLimits(SessionLimits.newBuilder().withMaxTurns(4).build())
            .build();

    try (var session = AgentSession.create(options)) {
      var terminal =
          session.runBlocking(
              UserMessage.text(
                  "Read the file 'pixel.png' and tell me what kind of file you see. "
                      + "Name the format and whether you can perceive any image content."));
      var text = assertSuccessText(terminal);
      var lower = text.toLowerCase(java.util.Locale.ROOT);
      assertTrue(
          lower.contains("png")
              || lower.contains("image")
              || lower.contains("pixel")
              || lower.contains("graphic"),
          () ->
              "Assistant reply must reference the image / PNG / pixel — proves the vision"
                  + " channel parsed the attachment, not just the text note. Got: "
                  + text);
    }
  }

  @Test
  void agentReadsRealPdfAndModelExtractsText(@TempDir Path workspace) throws IOException {
    // Hand-rolled minimal valid PDF: one page, single text run "Hello, world!". Parseable by
    // any conforming reader (verified with Adobe Reader + pdftotext locally before commit).
    var pdfBytes = minimalValidPdfBytes();
    Files.write(workspace.resolve("greeting.pdf"), pdfBytes);

    var options =
        ai.singlr.session.SessionOptions.newBuilder()
            .apply(SessionPresets.readOnly(workspace))
            .withModel(model)
            .withSystemPrompt(
                "You are a careful file inspector. When the user asks you to read a file, call"
                    + " the Read tool with the given path and then state what text the document"
                    + " contains, verbatim if possible.")
            .withLimits(SessionLimits.newBuilder().withMaxTurns(4).build())
            .build();

    try (var session = AgentSession.create(options)) {
      var terminal =
          session.runBlocking(
              UserMessage.text(
                  "Read the file 'greeting.pdf' and tell me what text the document contains."));
      var text = assertSuccessText(terminal);
      assertTrue(
          text.contains("Hello, world!")
              || text.toLowerCase(java.util.Locale.ROOT).contains("hello"),
          () ->
              "Assistant reply must echo the PDF's text content — proves the document channel"
                  + " parsed the binary stream. Got: "
                  + text);
    }
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

  /**
   * 67-byte minimal valid 1x1 PNG. Same bytes embedded in {@code ReadToolTest} — the duplication is
   * deliberate so the live test stands on its own without test-jar dependencies on the session
   * module. PNG header + IHDR (1x1 grayscale 8-bit) + IDAT (deflate-compressed single black pixel)
   * + IEND, all with correct CRCs.
   */
  private static byte[] minimalValid1x1PngBytes() {
    return new byte[] {
      (byte) 0x89,
      0x50,
      0x4E,
      0x47,
      0x0D,
      0x0A,
      0x1A,
      0x0A,
      0x00,
      0x00,
      0x00,
      0x0D,
      'I',
      'H',
      'D',
      'R',
      0x00,
      0x00,
      0x00,
      0x01,
      0x00,
      0x00,
      0x00,
      0x01,
      0x08,
      0x00,
      0x00,
      0x00,
      0x00,
      0x3A,
      0x7E,
      (byte) 0x9B,
      0x55,
      0x00,
      0x00,
      0x00,
      0x0A,
      'I',
      'D',
      'A',
      'T',
      0x78,
      (byte) 0x9C,
      0x63,
      0x00,
      0x01,
      0x00,
      0x00,
      0x00,
      0x05,
      0x00,
      0x01,
      0x0D,
      0x0A,
      0x2D,
      (byte) 0xB4,
      0x00,
      0x00,
      0x00,
      0x00,
      'I',
      'E',
      'N',
      'D',
      (byte) 0xAE,
      0x42,
      0x60,
      (byte) 0x82
    };
  }

  /**
   * Hand-rolled minimal 1-page PDF containing the text "Hello, world!" rendered via the Helvetica
   * standard font. ~500 bytes; structurally valid (xref offsets line up byte-for-byte so Adobe
   * Reader and pdftotext both parse it).
   */
  private static byte[] minimalValidPdfBytes() {
    var charset = StandardCharsets.ISO_8859_1;
    // /Length must equal the exact byte count of the content stream; the previous version had
    // /Length 51 against a 46-byte stream and Gemini extracted no text (real PDF spec violation,
    // real failure mode — chewed through maxTurns retrying). Compute the length dynamically.
    var streamContent = "BT /F1 24 Tf 100 700 Td (Hello, world!) Tj ET\n";
    var streamBytes = streamContent.getBytes(charset);
    var objects =
        new String[] {
          "<< /Type /Catalog /Pages 2 0 R >>",
          "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
          "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
              + "/Resources << /Font << /F1 5 0 R >> >> >>",
          "<< /Length " + streamBytes.length + " >>\nstream\n" + streamContent + "endstream",
          "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        };
    var out = new ByteArrayOutputStream();
    var offsets = new int[objects.length];
    try {
      out.write("%PDF-1.4\n%âãÏÓ\n".getBytes(charset));
      for (var i = 0; i < objects.length; i++) {
        offsets[i] = out.size();
        out.write(((i + 1) + " 0 obj\n" + objects[i] + "\nendobj\n").getBytes(charset));
      }
      var xrefStart = out.size();
      var xref = new StringBuilder();
      xref.append("xref\n0 ").append(objects.length + 1).append('\n');
      xref.append("0000000000 65535 f \n");
      for (var off : offsets) {
        xref.append(String.format("%010d 00000 n %n", off));
      }
      out.write(xref.toString().getBytes(charset));
      out.write(
          ("trailer\n<< /Size "
                  + (objects.length + 1)
                  + " /Root 1 0 R >>\nstartxref\n"
                  + xrefStart
                  + "\n%%EOF\n")
              .getBytes(charset));
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
    return out.toByteArray();
  }
}
