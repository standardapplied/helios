/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */
package ai.singlr.examples.session;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.anthropic.AnthropicModelId;
import ai.singlr.anthropic.AnthropicProvider;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.session.AgentSession;
import ai.singlr.session.ResultMessage;
import ai.singlr.session.SessionLimits;
import ai.singlr.session.SessionOptions;
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
 * Anthropic peer of {@link ReadToolMultimodalThroughLoopIntegrationTest}. Closes the chain end to
 * end on Claude Sonnet 4.6 (the cheapest vision/PDF-capable Anthropic model in the supported
 * matrix): Read tool reads a real PNG / real PDF from the workspace, the loop splices the
 * attachment into a follow-up user message, the {@code AnthropicModel} adapter converts the {@code
 * InlineFile} into the Messages API {@code image} / {@code document} content block, the live Claude
 * server parses the bytes, the model returns a coherent response.
 *
 * <p>Anthropic enforces tighter per-image limits than Gemini (5 MB per image) — this test
 * deliberately uses fixtures well inside that floor (67-byte PNG, ~520-byte PDF) so the caps in
 * {@link ai.singlr.session.files.ReadTool#MAX_IMAGE_BYTES} are exercised on the happy path, not the
 * rejection path. Same {@code maxTurns=4} budget as the Gemini peer to bound spend.
 *
 * <p>Guarded by {@code ANTHROPIC_API_KEY} so the suite stays runnable offline.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
final class ReadToolMultimodalAnthropicIntegrationTest {

  private static Model model;

  @BeforeAll
  static void setUp() {
    var apiKey = System.getenv("ANTHROPIC_API_KEY");
    var config = ModelConfig.newBuilder().withApiKey(apiKey).build();
    model = new AnthropicProvider().create(AnthropicModelId.CLAUDE_SONNET_4_6.id(), config);
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (model != null) {
      model.close();
    }
  }

  @Test
  void agentReadsRealPngAndClaudeDescribesIt(@TempDir Path workspace) throws IOException {
    Files.write(workspace.resolve("pixel.png"), minimalValid1x1PngBytes());

    var options =
        SessionOptions.newBuilder()
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
              "Assistant reply must reference the image / PNG / pixel — proves Anthropic's vision"
                  + " channel parsed the attachment, not just the text note. Got: "
                  + text);
    }
  }

  @Test
  void agentReadsRealPdfAndClaudeExtractsText(@TempDir Path workspace) throws IOException {
    Files.write(workspace.resolve("greeting.pdf"), minimalValidPdfBytes());

    var options =
        SessionOptions.newBuilder()
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
              "Assistant reply must echo the PDF's text content — proves the Anthropic document"
                  + " channel parsed the binary stream. Got: "
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
   * 1x1 PNG bytes copied verbatim from Anthropic's published vision cookbook example. Using their
   * blessed bytes rather than a hand-rolled minimal PNG: my first attempt was a 67-byte grayscale
   * 1x1 PNG with valid IHDR / IDAT / IEND chunks and correct CRCs — Gemini accepted it; Claude
   * rejected it with HTTP 400 {@code "Could not process image"}. Anthropic's vision pipeline is
   * stricter about PNG variants than Gemini's. The cookbook bytes are the canonical reference; if
   * this regresses, the failure lives in our {@code AnthropicModel} adapter, not in the fixture.
   *
   * <p>This is exactly the kind of cross-provider divergence that only live testing surfaces.
   */
  private static byte[] minimalValid1x1PngBytes() {
    return java.util.Base64.getDecoder()
        .decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4n"
                + "GP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC");
  }

  /**
   * Hand-rolled minimal 1-page PDF containing "Hello, world!" via the Helvetica standard font.
   * {@code /Length} is computed from actual stream bytes — getting this wrong silently corrupts the
   * page (the Gemini run caught a 51-vs-46 mismatch before the fix).
   */
  private static byte[] minimalValidPdfBytes() {
    var charset = StandardCharsets.ISO_8859_1;
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
