/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import static com.standardapplied.helios.session.test.BindingCalls.keyOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.test.SampleDocuments;
import com.standardapplied.helios.session.tools.ToolCategory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class ReadToolTest {

  @Test
  void readsFileWithLineNumbers(@TempDir Path tmp) throws IOException {
    var file = tmp.resolve("hello.txt");
    Files.writeString(file, "alpha\nbeta\ngamma", StandardCharsets.UTF_8);
    var ws = WorkspaceRoot.of(tmp);

    var binding = ReadTool.binding(ws);
    var result = binding.tool().execute(Map.of("path", "hello.txt"), ToolContext.noop());

    assertTrue(result.succeeded(), result.output());
    var out = result.output();
    assertTrue(out.contains("     1\talpha\n"), out);
    assertTrue(out.contains("     2\tbeta\n"), out);
    assertTrue(out.contains("     3\tgamma\n"), out);
    assertEquals(ToolCategory.READ, binding.category());
    assertEquals("./hello.txt", keyOf(binding, Map.of("path", "hello.txt")).canonicalArgs());
  }

  @Test
  void offsetAndLimitPaginate(@TempDir Path tmp) throws IOException {
    var file = tmp.resolve("nums.txt");
    Files.writeString(file, "one\ntwo\nthree\nfour\nfive\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "nums.txt", "offset", 2, "limit", 2), ToolContext.noop());

    assertTrue(result.succeeded(), result.output());
    var out = result.output();
    assertTrue(out.contains("     2\ttwo\n"), out);
    assertTrue(out.contains("     3\tthree\n"), out);
    assertFalse(out.contains("one"));
    assertFalse(out.contains("four"));
    // Truncation marker teaches the model the next move — offset to continue or Grep narrower.
    assertTrue(out.contains("[truncated at line 3"), out);
    assertTrue(out.contains("Use offset=4 to continue"), out);
    assertTrue(out.contains("Grep for a narrower target"), out);
  }

  @Test
  void acceptsLongOffsetAndLimit(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("nums.txt"), "a\nb\nc\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "nums.txt", "offset", 1L, "limit", 1L), ToolContext.noop());
    assertTrue(result.succeeded());
    assertTrue(result.output().contains("     1\ta"));
  }

  @Test
  void acceptsNumericOffsetAndLimit(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("nums.txt"), "a\nb\nc\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(
                Map.of("path", "nums.txt", "offset", (short) 2, "limit", (short) 1),
                ToolContext.noop());
    assertTrue(result.succeeded());
    assertTrue(result.output().contains("     2\tb"), result.output());
  }

  @Test
  void missingPathArgFails(@TempDir Path tmp) {
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of(), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().contains("missing required 'path'"), result.output());
  }

  @Test
  void invalidOffsetFails(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("x.txt"), "a\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "x.txt", "offset", 0), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().contains("'offset' must be >= 1"), result.output());
  }

  @Test
  void invalidLimitFails(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("x.txt"), "a\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "x.txt", "limit", 0), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().contains("'limit' must be >= 1"), result.output());
  }

  @Test
  void notARegularFileFails(@TempDir Path tmp) throws IOException {
    Files.createDirectory(tmp.resolve("dir"));
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "dir"), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().contains("not a regular file"), result.output());
  }

  @Test
  void escapingWorkspaceFails(@TempDir Path tmp) {
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "../escape.txt"), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().startsWith("Read:"), result.output());
  }

  @Test
  void readingMissingFileFails(@TempDir Path tmp) {
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "nope.txt"), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().contains("not a regular file"), result.output());
  }

  @Test
  void nameConstantMatchesBinding(@TempDir Path tmp) {
    var binding = ReadTool.binding(WorkspaceRoot.of(tmp));
    assertEquals("Read", binding.name());
    assertEquals("Read", ReadTool.NAME);
  }

  @Test
  void rejectsNullWorkspace() {
    assertThrows(NullPointerException.class, () -> ReadTool.binding(null));
  }

  @Test
  void aFileReplacedBetweenTwoReadsComesBackWithItsNewContent(@TempDir Path tmp)
      throws IOException {
    var file = tmp.resolve("notes.txt");
    Files.writeString(file, "first\n", StandardCharsets.UTF_8);
    var tool = ReadTool.binding(WorkspaceRoot.of(tmp)).tool();

    var before = tool.execute(Map.of("path", "./notes.txt"), ToolContext.noop());
    Files.delete(file);
    Files.writeString(file, "second\n", StandardCharsets.UTF_8);
    var after = tool.execute(Map.of("path", "./notes.txt"), ToolContext.noop());

    assertEquals("     1\tfirst\n", before.output());
    assertEquals("     1\tsecond\n", after.output());
  }

  // ── Layer 1: bounded text reading (file size, byte budget, binary detect) ──

  @Test
  void rejectsOversizedFileBeforeReading(@TempDir Path tmp) throws IOException {
    // Pre-fix: ReadTool would Files.readString() the whole file then trim. This test pins the
    // fail-fast behavior: the source-size cap rejects before any read happens.
    var file = tmp.resolve("huge.txt");
    // Build a 26 MB file (>MAX_FILE_SIZE_BYTES = 25 MB). One MB chunk written 26 times.
    var chunk = "x".repeat(1024 * 1024).getBytes(StandardCharsets.UTF_8);
    try (var out = Files.newOutputStream(file)) {
      for (var i = 0; i < 26; i++) {
        out.write(chunk);
      }
    }
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "huge.txt"), ToolContext.noop());
    assertFalse(result.succeeded(), result.output());
    assertTrue(result.output().contains("exceeds maximum size"), result.output());
    assertTrue(
        result.output().contains("Grep") || result.output().contains("split"),
        "error must teach the model the next move: " + result.output());
  }

  @Test
  void perLineCapTruncatesLongLineWithMarker(@TempDir Path tmp) throws IOException {
    // A pathological single-line file (e.g. minified JSON) gets truncated at MAX_LINE_CHARS with
    // an inline marker, instead of streaming the whole blob into the model context.
    var file = tmp.resolve("long-line.txt");
    Files.writeString(file, "a".repeat(1024 * 1024 + 100) + "\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "long-line.txt"), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    assertTrue(result.output().contains("[line truncated to"), result.output());
    // And a closing note at the bottom.
    assertTrue(result.output().contains("exceeded"), result.output());
  }

  @Test
  void totalOutputBudgetTruncatesPathologicalFile(@TempDir Path tmp) throws IOException {
    // Many short lines, total exceeding MAX_OUTPUT_CHARS but each under MAX_LINE_CHARS and total
    // file under MAX_FILE_SIZE_BYTES. Without the total-chars cap a per-line cap alone wouldn't
    // save us. Cap kicks in mid-stream.
    var file = tmp.resolve("many-lines.txt");
    try (var out =
        new java.io.BufferedWriter(
            new java.io.OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8))) {
      var line = "y".repeat(99);
      for (var i = 0; i < 50_000; i++) {
        out.write(line);
        out.newLine();
      }
    }
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(
                Map.of("path", "many-lines.txt", "limit", 1_000_000),
                ToolContext.noop()); // big limit so the chars budget is the cap that fires
    assertTrue(result.succeeded(), result.output());
    assertTrue(result.output().contains("[truncated: total output exceeded"), result.output());
    assertTrue(result.output().contains("Grep"), result.output());
  }

  @Test
  void rejectsUnknownBinaryWithGuidance(@TempDir Path tmp) throws IOException {
    // ELF-like header with a NUL byte — recognised as binary by the sniff, no MIME → fail-fast
    // with a message that names the detected MIME and points to a different tool.
    var file = tmp.resolve("blob.dat");
    Files.write(file, new byte[] {0x7f, 'E', 'L', 'F', 0x00, 0x01, 0x02, 0x03, 0x04});
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "blob.dat"), ToolContext.noop());
    assertFalse(result.succeeded(), result.output());
    assertTrue(
        result.output().contains("binary file") || result.output().contains("refusing to decode"),
        result.output());
  }

  // ── Layer 3: multimodal dispatch (PDF + image attachments) ─────────────

  @Test
  void pngImageReturnsAsInlineFileAttachment(@TempDir Path tmp) throws IOException {
    var file = tmp.resolve("chart.png");
    // 8-byte PNG signature + minimal IHDR — Files.probeContentType picks this up by extension on
    // every supported JDK. The provider's vision channel decodes the bytes; we just pass them
    // through.
    var pngHeader =
        new byte[] {
          (byte) 0x89,
          'P',
          'N',
          'G',
          '\r',
          '\n',
          0x1a,
          '\n',
          0x00,
          0x00,
          0x00,
          0x0d,
          'I',
          'H',
          'D',
          'R'
        };
    Files.write(file, pngHeader);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "chart.png"), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    assertTrue(result.hasAttachments(), "PNG must come back as a multimodal attachment");
    assertEquals(1, result.attachments().size());
    var attachment = result.attachments().getFirst();
    assertEquals("image/png", attachment.mimeType());
    assertTrue(result.output().contains("image/png"), result.output());
    assertTrue(result.output().contains("multimodal attachment"), result.output());
  }

  @Test
  void pdfReturnsAsInlineFileAttachment(@TempDir Path tmp) throws IOException {
    var file = tmp.resolve("paper.pdf");
    Files.write(file, "%PDF-1.4\n%a\n".getBytes(StandardCharsets.UTF_8));
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "paper.pdf"), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    assertTrue(result.hasAttachments(), "PDF must come back as a multimodal attachment");
    var attachment = result.attachments().getFirst();
    assertEquals("application/pdf", attachment.mimeType());
  }

  @Test
  void anImageOneByteOverItsCapIsRejectedNamingTheCap(@TempDir Path tmp) throws IOException {
    Files.write(tmp.resolve("big.png"), png((int) ReadTool.MAX_IMAGE_BYTES + 1));

    var result = read(tmp, "big.png");

    assertEquals(
        "Read: image/png file 'big.png' exceeds inline attachment limit of 7500000 bytes (was"
            + " 7500001). The file is resent with every turn; route a file this large through a"
            + " file-upload host tool.",
        result.output());
  }

  @Test
  void aPdfAtItsCapIsAnAttachmentAndOneByteMoreIsRejected(@TempDir Path tmp) throws IOException {
    Files.write(tmp.resolve("at-cap.pdf"), pdf((int) ReadTool.MAX_PDF_BYTES));
    Files.write(tmp.resolve("over-cap.pdf"), pdf((int) ReadTool.MAX_PDF_BYTES + 1));

    var atCap = read(tmp, "at-cap.pdf");
    var overCap = read(tmp, "over-cap.pdf");

    assertTrue(atCap.succeeded(), atCap.output());
    assertEquals(ReadTool.MAX_PDF_BYTES, atCap.attachments().getFirst().data().length);
    assertEquals(
        "Read: application/pdf file 'over-cap.pdf' exceeds inline attachment limit of 18000000"
            + " bytes (was 18000001). The file is resent with every turn; route a file this large"
            + " through a file-upload host tool.",
        overCap.output());
  }

  @Test
  void theAttachmentCapsEncodeToAnthropicsLimits() {
    assertEquals(
        24_000_000, Base64.getEncoder().encode(new byte[(int) ReadTool.MAX_PDF_BYTES]).length);
    assertEquals(
        10_000_000, Base64.getEncoder().encode(new byte[(int) ReadTool.MAX_IMAGE_BYTES]).length);
  }

  @Test
  void imageAtImageCapIsAccepted(@TempDir Path tmp) throws IOException {
    // Exactly MAX_IMAGE_BYTES (5 MB) is the boundary — must succeed. Verifies the comparison is
    // strictly greater-than rather than greater-or-equal.
    var file = tmp.resolve("borderline.png");
    Files.write(file, png((int) ReadTool.MAX_IMAGE_BYTES));
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "borderline.png"), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    assertTrue(result.hasAttachments());
  }

  @Test
  void realPngReachesProviderAsInlineFileBytesUnchanged(@TempDir Path tmp) throws IOException {
    // Higher-fidelity than the 8-byte-header tests above: an actual valid 1x1 PNG with
    // IHDR/IDAT/IEND chunks and correct CRCs, from bytes rather than ImageIO so the test stays
    // inside java.base (the session module doesn't read java.desktop). Proves the bytes are
    // pristine from filesystem → ToolResult.attachments — no UTF-8 mangling, no line-ending
    // rewriting, no partial-buffer reads.
    var pngBytes = SampleDocuments.pixelPng();
    Files.write(tmp.resolve("real.png"), pngBytes);

    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "real.png"), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    assertEquals(1, result.attachments().size());
    var attachment = result.attachments().getFirst();
    assertEquals("image/png", attachment.mimeType());
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        pngBytes,
        attachment.data(),
        "PNG bytes must survive ReadTool unchanged — every byte including CRCs preserved");
    // PNG signature explicitly checked so a future refactor that accidentally rewrites bytes
    // (e.g. via String.getBytes(UTF_8) anywhere on the path) trips here, not at the provider.
    assertEquals((byte) 0x89, attachment.data()[0]);
    assertEquals((byte) 'P', attachment.data()[1]);
    assertEquals((byte) 'N', attachment.data()[2]);
    assertEquals((byte) 'G', attachment.data()[3]);
  }

  @Test
  void realPdfDocumentReachesProviderAsInlineFile(@TempDir Path tmp) throws IOException {
    // Hand-rolled minimal PDF — a single-page document with "Hello, world!" content. This is a
    // STRUCTURALLY valid PDF (parseable by PDF readers, accepted by Anthropic / Gemini document
    // channels), not just the %PDF-1.4 magic. Bytes are written verbatim and re-checked at the
    // attachment boundary to prove no UTF-8 or line-ending mangling.
    var pdfBytes = SampleDocuments.helloWorldPdf();
    Files.write(tmp.resolve("paper.pdf"), pdfBytes);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "paper.pdf"), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    var attachment = result.attachments().getFirst();
    assertEquals("application/pdf", attachment.mimeType());
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        pdfBytes,
        attachment.data(),
        "PDF bytes must survive ReadTool unchanged — no UTF-8 decode, no line-ending mangling");
    // Spot-check structural markers — if a future refactor accidentally decodes-then-re-encodes
    // the bytes, the binary streams inside the PDF would corrupt and these would drift.
    var content = new String(attachment.data(), java.nio.charset.StandardCharsets.ISO_8859_1);
    assertTrue(content.startsWith("%PDF-1.4"), "PDF header preserved");
    assertTrue(content.contains("%%EOF"), "PDF EOF marker preserved");
    assertTrue(content.contains("Hello, world!"), "PDF text content preserved");
  }

  @Test
  void mimeProbeFallsBackToExtensionForCommonTypes(@TempDir Path tmp) throws IOException {
    // Some minimal containers have no system MIME registry — verify the JDK probe fallback
    // path returns text/markdown / application/json without throwing.
    Files.writeString(tmp.resolve("notes.md"), "# title\n", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("data.json"), "{\"x\":1}", StandardCharsets.UTF_8);
    var binding = ReadTool.binding(WorkspaceRoot.of(tmp));

    var md = binding.tool().execute(Map.of("path", "notes.md"), ToolContext.noop());
    assertTrue(md.succeeded(), md.output());
    assertTrue(md.output().contains("title"));

    var json = binding.tool().execute(Map.of("path", "data.json"), ToolContext.noop());
    assertTrue(json.succeeded(), json.output());
    assertTrue(json.output().contains("\"x\":1"));
  }

  @Test
  void uncategorisedButCleanFileServedAsText(@TempDir Path tmp) throws IOException {
    // No extension at all → probe returns null → NUL-sniff fallback kicks in → served as text.
    // This is the "config file with no extension" path we want to support.
    Files.writeString(
        tmp.resolve("Dockerfile_no_extension"),
        "FROM alpine\nRUN apk add --no-cache foo\n",
        StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "Dockerfile_no_extension"), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    assertTrue(result.output().contains("FROM alpine"));
  }

  @Test
  void successResultHasNoAttachmentsForPlainText(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("note.txt"), "hello\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "note.txt"), ToolContext.noop());
    assertTrue(result.succeeded());
    assertFalse(result.hasAttachments(), "text results carry no attachments");
    assertEquals(0, result.attachments().size());
  }

  // ── MIME detection branch coverage ─────────────────────────────────────

  /**
   * Covers every extension branch in {@link MimeTypes#detect}. Each test in this batch writes a
   * tiny file with the named extension and asserts the dispatch happens correctly — text files come
   * back with their content, binary-attachable types come back as attachments, everything else gets
   * the right MIME from the extension fallback. Coverage gate guard.
   */
  @Test
  void mimeDispatchCoversTextExtensions(@TempDir Path tmp) throws IOException {
    // Text-like extensions all route to the bounded-text path.
    var textExts =
        List.of(
            "html",
            "htm",
            "css",
            "js",
            "mjs",
            "java",
            "kt",
            "scala",
            "py",
            "rb",
            "go",
            "rs",
            "c",
            "cpp",
            "h",
            "hpp",
            "ts",
            "tsx",
            "md",
            "markdown",
            "csv",
            "tsv",
            "log",
            "txt",
            "xml",
            "yaml",
            "yml");
    for (var ext : textExts) {
      var file = tmp.resolve("file." + ext);
      Files.writeString(file, "content\n", StandardCharsets.UTF_8);
      var result =
          ReadTool.binding(WorkspaceRoot.of(tmp))
              .tool()
              .execute(Map.of("path", "file." + ext), ToolContext.noop());
      assertTrue(result.succeeded(), ext + " should be text-readable: " + result.output());
      assertTrue(result.output().contains("content"), ext);
      assertFalse(result.hasAttachments(), ext + " should not have attachments");
    }
  }

  @Test
  void mimeDispatchCoversImageExtensions(@TempDir Path tmp) throws IOException {
    // Each image extension routes to the attachment path. Use minimal-but-valid file headers
    // so probeContentType picks them up by content rather than extension on every JDK.
    var bytes = new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    var imgExts = List.of("png", "jpg", "jpeg", "gif", "webp");
    for (var ext : imgExts) {
      var file = tmp.resolve("img." + ext);
      Files.write(file, bytes);
      var result =
          ReadTool.binding(WorkspaceRoot.of(tmp))
              .tool()
              .execute(Map.of("path", "img." + ext), ToolContext.noop());
      assertTrue(result.succeeded(), ext + ": " + result.output());
      assertTrue(result.hasAttachments(), ext + " should be an attachment");
      assertTrue(
          result.attachments().getFirst().mimeType().startsWith("image/"),
          ext + " MIME: " + result.attachments().getFirst().mimeType());
    }
  }

  @Test
  void unknownExtensionWithCleanContentServedAsText(@TempDir Path tmp) throws IOException {
    // Extension we don't enumerate → probeContentType might return null on some platforms →
    // NUL-sniff fallback recognises it as text.
    Files.writeString(tmp.resolve("config.unknown"), "key=value\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "config.unknown"), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    assertTrue(result.output().contains("key=value"), result.output());
  }

  @Test
  void emptyFileServedAsTextWithNoOutput(@TempDir Path tmp) throws IOException {
    // Empty file: text path, empty output, no truncation marker. Edge case worth pinning so
    // future refactors don't accidentally emit synthetic markers for the empty case.
    var file = tmp.resolve("empty.txt");
    Files.createFile(file);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "empty.txt"), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    assertEquals("", result.output());
  }

  @Test
  void exactLimitNoTruncationMarker(@TempDir Path tmp) throws IOException {
    // Exactly limit lines, exactly EOF — no truncation marker should appear. Boundary test for
    // the limit logic; off-by-one here used to add a spurious marker pre-refactor.
    Files.writeString(tmp.resolve("five.txt"), "a\nb\nc\nd\ne\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "five.txt", "limit", 5), ToolContext.noop());
    assertTrue(result.succeeded());
    assertFalse(
        result.output().contains("[truncated"),
        "reading exactly limit lines should NOT emit a truncation marker: " + result.output());
  }

  @Test
  void aLineCutInsideASecretKeepsNoPartOfIt(@TempDir Path tmp) throws IOException {
    var secret = "sk-test-0123456789abcdef";
    var registry = new SecretRegistry();
    registry.register("API_KEY", secret);
    var kept = "x".repeat(ReadTool.MAX_LINE_CHARS - 10);
    Files.writeString(
        tmp.resolve("long.txt"), kept + secret + " tail\nshort sk-test-\n", StandardCharsets.UTF_8);
    var ctx =
        new ToolContext(
            new SessionContext("read-cut", new CancellationToken(), Clock.systemUTC(), registry),
            new CancellationToken(),
            Duration.ofDays(1),
            key -> false);

    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of("path", "long.txt"), ctx);

    assertTrue(result.succeeded(), result.output());
    var lines = result.output().split("\n");
    assertEquals(
        String.format("%6d\t%s", 1, kept)
            + " [line truncated to "
            + ReadTool.MAX_LINE_CHARS
            + " chars]",
        lines[0]);
    assertEquals(String.format("%6d\t%s", 2, "short sk-test-"), lines[1]);
  }

  @Test
  void publicConstantsHaveTheDocumentedValues() {
    assertEquals(2000, ReadTool.DEFAULT_LIMIT);
    assertEquals(25L * 1024 * 1024, ReadTool.MAX_FILE_SIZE_BYTES);
    assertEquals(2000, ReadTool.MAX_LINE_CHARS);
    assertEquals(60_000, ReadTool.MAX_OUTPUT_CHARS);
    assertEquals(7_500_000, ReadTool.MAX_IMAGE_BYTES);
    assertEquals(18_000_000, ReadTool.MAX_PDF_BYTES);
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void symlinkOutsideWorkspaceIsRefusedAndInsideIsReadAtItsCanonicalPath(@TempDir Path tmp)
      throws IOException {
    var root = Files.createDirectory(tmp.resolve("ws"));
    var outside = Files.createDirectory(tmp.resolve("outside"));
    Files.writeString(outside.resolve("secret.txt"), "SENTINEL\n", StandardCharsets.UTF_8);
    Files.writeString(root.resolve("inside.txt"), "inside\n", StandardCharsets.UTF_8);
    Files.createSymbolicLink(root.resolve("leak.txt"), outside.resolve("secret.txt"));
    Files.createSymbolicLink(root.resolve("alias.txt"), root.resolve("inside.txt"));
    Files.createSymbolicLink(root.resolve("dangling.txt"), root.resolve("gone.txt"));
    var tool = ReadTool.binding(WorkspaceRoot.of(root)).tool();

    var leak = tool.execute(Map.of("path", "leak.txt"), ToolContext.noop());
    assertFalse(leak.succeeded());
    assertFalse(leak.output().contains("SENTINEL"), leak.output());

    var dangling = tool.execute(Map.of("path", "dangling.txt"), ToolContext.noop());
    assertFalse(dangling.succeeded());

    var throughTheLink = tool.execute(Map.of("path", "alias.txt"), ToolContext.noop());
    assertEquals(
        "Read: path is not canonical or changed after it was authorised: alias.txt now resolves"
            + " to ./inside.txt",
        throughTheLink.output());

    var alias =
        tool.execute(
            Map.of("path", WorkspaceRoot.of(root).canonical("alias.txt")), ToolContext.noop());
    assertTrue(alias.succeeded(), alias.output());
    assertTrue(alias.output().contains("inside"), alias.output());
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void specialFileIsRefusedBeforeOpen(@TempDir Path tmp) {
    Assumptions.assumeTrue(SpecialFiles.mkfifo(tmp.resolve("pipe.txt")));
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "pipe.txt"), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().contains("not a regular file"), result.output());
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void unsearchableParentIsAnIoErrorReadingTheSize(@TempDir Path tmp) throws IOException {
    var dir = Files.createDirectory(tmp.resolve("locked"));
    Files.writeString(dir.resolve("a.txt"), "x", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("---------"));
    try {
      Assumptions.assumeFalse(Files.isExecutable(dir), "permissions are not enforced (root)");
      var result =
          ReadTool.binding(WorkspaceRoot.of(tmp))
              .tool()
              .execute(Map.of("path", "locked/a.txt"), ToolContext.noop());
      assertFalse(result.succeeded());
      assertTrue(result.output().startsWith("Read: I/O error reading size: "), result.output());
    } finally {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void anUnreadableFileIsAnIoErrorReadingIt(@TempDir Path tmp) throws IOException {
    var locked = Files.writeString(tmp.resolve("a.txt"), "x", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
    try {
      Assumptions.assumeFalse(Files.isReadable(locked), "permissions are not enforced (root)");
      var result =
          ReadTool.binding(WorkspaceRoot.of(tmp))
              .tool()
              .execute(Map.of("path", "a.txt"), ToolContext.noop());
      assertFalse(result.succeeded());
      assertTrue(result.output().startsWith("Read: I/O error reading file: "), result.output());
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-------"));
    }
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void offsetAndLimitAreValidatedBeforeTheFileIsRead(@TempDir Path tmp) throws IOException {
    var locked = Files.writeString(tmp.resolve("a.txt"), "x", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
    try {
      Assumptions.assumeFalse(Files.isReadable(locked), "permissions are not enforced (root)");
      var tool = ReadTool.binding(WorkspaceRoot.of(tmp)).tool();

      var offset = tool.execute(Map.of("path", "a.txt", "offset", 0), ToolContext.noop());
      var limit = tool.execute(Map.of("path", "a.txt", "limit", 0), ToolContext.noop());

      assertEquals("Read: 'offset' must be >= 1, got 0", offset.output());
      assertEquals("Read: 'limit' must be >= 1, got 0", limit.output());
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-------"));
    }
  }

  @Test
  void aPageStopsAtTheCharsCapAndTheNextOffsetContinuesWhereItStopped(@TempDir Path tmp)
      throws IOException {
    var text = new StringBuilder();
    for (var i = 1; text.length() < 200_000; i++) {
      text.append(String.format("line %05d %s%n", i, "z".repeat(40)));
    }
    Files.writeString(tmp.resolve("long.txt"), text, StandardCharsets.UTF_8);

    var first = read(tmp, "long.txt").output();
    var marker = first.substring(first.lastIndexOf('['));
    var content = first.substring(0, first.length() - marker.length());
    var next = Integer.parseInt(marker.replaceAll("(?s).*Use offset=(\\d+) .*", "$1"));
    var second = read(tmp, "long.txt", Map.of("offset", next)).output();

    assertTrue(content.length() <= ReadTool.MAX_OUTPUT_CHARS, () -> "page of " + content.length());
    assertTrue(marker.startsWith("[truncated: total output exceeded 60000 chars after "), marker);
    assertTrue(
        content.endsWith(
            String.format("%6d\tline %05d ", next - 1, next - 1) + "z".repeat(40) + "\n"),
        content);
    assertTrue(second.startsWith(String.format("%6d\tline %05d ", next, next)), second);
  }

  @Test
  void aFirstLineLongerThanThePageIsCutSoPagingAdvances(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("wide.txt"), "w".repeat(500_000) + "\nsecond\n", StandardCharsets.UTF_8);

    var result = read(tmp, "wide.txt", Map.of("limit", 1));

    assertEquals(
        String.format("%6d\t%s [line truncated to 2000 chars]%n", 1, "w".repeat(2000))
            + "[truncated at line 1; 1 lines emitted. Use offset=2 to continue, or Grep for a"
            + " narrower target.]\n",
        result.output());
  }

  private static ToolResult read(Path root, String path) {
    return read(root, path, Map.of());
  }

  private static ToolResult read(Path root, String path, Map<String, Object> page) {
    var args = new HashMap<String, Object>(page);
    args.put("path", path);
    return ReadTool.binding(WorkspaceRoot.of(root)).tool().execute(args, ToolContext.noop());
  }

  private static byte[] png(int size) {
    var bytes = new byte[size];
    var header = new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    System.arraycopy(header, 0, bytes, 0, header.length);
    return bytes;
  }

  private static byte[] pdf(int size) {
    var bytes = new byte[size];
    var header = "%PDF-1.4\n".getBytes(StandardCharsets.UTF_8);
    System.arraycopy(header, 0, bytes, 0, header.length);
    return bytes;
  }
}
