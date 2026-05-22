/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.session.tools.ToolCategory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReadToolTest {

  @Test
  void readsFileWithLineNumbers(@TempDir Path tmp) throws IOException {
    var file = tmp.resolve("hello.txt");
    Files.writeString(file, "alpha\nbeta\ngamma", StandardCharsets.UTF_8);
    var ws = WorkspaceRoot.of(tmp);
    var tracker = InMemoryFileTracker.create();

    var binding = ReadTool.binding(ws, tracker);
    var result = binding.tool().execute(Map.of("path", "hello.txt"));

    assertTrue(result.success(), result.output());
    var out = result.output();
    assertTrue(out.contains("     1\talpha\n"), out);
    assertTrue(out.contains("     2\tbeta\n"), out);
    assertTrue(out.contains("     3\tgamma\n"), out);
    assertEquals(ToolCategory.READ, binding.category());
    assertEquals("hello.txt", binding.permissionKey(Map.of("path", "hello.txt")).canonicalArgs());
    assertTrue(tracker.hasReadInSession(file));
  }

  @Test
  void offsetAndLimitPaginate(@TempDir Path tmp) throws IOException {
    var file = tmp.resolve("nums.txt");
    Files.writeString(file, "one\ntwo\nthree\nfour\nfive\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "nums.txt", "offset", 2, "limit", 2));

    assertTrue(result.success(), result.output());
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
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "nums.txt", "offset", 1L, "limit", 1L));
    assertTrue(result.success());
    assertTrue(result.output().contains("     1\ta"));
  }

  @Test
  void acceptsNumericOffsetAndLimit(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("nums.txt"), "a\nb\nc\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "nums.txt", "offset", (short) 2, "limit", (short) 1));
    assertTrue(result.success());
    assertTrue(result.output().contains("     2\tb"), result.output());
  }

  @Test
  void missingPathArgFails(@TempDir Path tmp) {
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of());
    assertFalse(result.success());
    assertTrue(result.output().contains("missing required 'path'"), result.output());
  }

  @Test
  void invalidOffsetFails(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("x.txt"), "a\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "x.txt", "offset", 0));
    assertFalse(result.success());
    assertTrue(result.output().contains("'offset' must be >= 1"), result.output());
  }

  @Test
  void invalidLimitFails(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("x.txt"), "a\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "x.txt", "limit", 0));
    assertFalse(result.success());
    assertTrue(result.output().contains("'limit' must be >= 1"), result.output());
  }

  @Test
  void notARegularFileFails(@TempDir Path tmp) throws IOException {
    Files.createDirectory(tmp.resolve("dir"));
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "dir"));
    assertFalse(result.success());
    assertTrue(result.output().contains("not a regular file"), result.output());
  }

  @Test
  void escapingWorkspaceFails(@TempDir Path tmp) {
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "../escape.txt"));
    assertFalse(result.success());
    assertTrue(result.output().startsWith("Read:"), result.output());
  }

  @Test
  void readingMissingFileFails(@TempDir Path tmp) {
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "nope.txt"));
    assertFalse(result.success());
    assertTrue(result.output().contains("not a regular file"), result.output());
  }

  @Test
  void nameConstantMatchesBinding(@TempDir Path tmp) {
    var binding = ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create());
    assertEquals("Read", binding.name());
    assertEquals("Read", ReadTool.NAME);
  }

  @Test
  void rejectsNullWorkspace() {
    assertThrows(
        NullPointerException.class, () -> ReadTool.binding(null, InMemoryFileTracker.create()));
  }

  @Test
  void rejectsNullTracker(@TempDir Path tmp) {
    assertThrows(NullPointerException.class, () -> ReadTool.binding(WorkspaceRoot.of(tmp), null));
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
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "huge.txt"));
    assertFalse(result.success(), result.output());
    assertTrue(result.output().contains("exceeds maximum size"), result.output());
    assertTrue(
        result.output().contains("Grep") || result.output().contains("split"),
        "error must teach the model the next move: " + result.output());
  }

  @Test
  void perLineCapTruncatesLongLineWithMarker(@TempDir Path tmp) throws IOException {
    // A pathological 2 MB single-line file (e.g. minified JSON) gets truncated at MAX_LINE_BYTES
    // (1 MB) with an inline marker, instead of streaming the whole blob into the model context.
    var file = tmp.resolve("long-line.txt");
    Files.writeString(file, "a".repeat(1024 * 1024 + 100) + "\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "long-line.txt"));
    assertTrue(result.success(), result.output());
    assertTrue(result.output().contains("[line truncated to"), result.output());
    // And a closing note at the bottom.
    assertTrue(result.output().contains("exceeded"), result.output());
  }

  @Test
  void totalOutputBudgetTruncatesPathologicalFile(@TempDir Path tmp) throws IOException {
    // Many short lines, total exceeding MAX_OUTPUT_BYTES (4 MB) but each under MAX_LINE_BYTES
    // and total file under MAX_FILE_SIZE_BYTES. Without the total-bytes cap a per-line cap
    // alone wouldn't save us. Cap kicks in mid-stream.
    var file = tmp.resolve("many-lines.txt");
    // 5 MB worth of 100-byte lines, well over the 4 MB output budget but under file-size cap.
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
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(
                Map.of(
                    "path",
                    "many-lines.txt",
                    "limit",
                    1_000_000)); // big limit so byte budget is the cap that fires
    assertTrue(result.success(), result.output());
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
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "blob.dat"));
    assertFalse(result.success(), result.output());
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
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "chart.png"));
    assertTrue(result.success(), result.output());
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
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "paper.pdf"));
    assertTrue(result.success(), result.output());
    assertTrue(result.hasAttachments(), "PDF must come back as a multimodal attachment");
    var attachment = result.attachments().getFirst();
    assertEquals("application/pdf", attachment.mimeType());
  }

  @Test
  void oversizedImageAttachmentIsRejectedAtImageCap(@TempDir Path tmp) throws IOException {
    // Image just above MAX_IMAGE_BYTES (5 MB, Anthropic floor) — fails at the image-specific
    // cap with a message that names Anthropic's limit and the recovery move (downsample /
    // re-encode). Source-file size (~5.5 MB) is well under MAX_FILE_SIZE_BYTES so the
    // image-specific cap is the one that fires.
    var file = tmp.resolve("big.png");
    var pngHeader = new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    var filler = new byte[(int) ReadTool.MAX_IMAGE_BYTES]; // 5 MB + 8-byte header > 5 MB cap
    try (var out = Files.newOutputStream(file)) {
      out.write(pngHeader);
      out.write(filler);
    }
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "big.png"));
    assertFalse(result.success(), result.output());
    assertTrue(result.output().contains("exceeds inline attachment limit"), result.output());
    assertTrue(
        result.output().contains("downsample") || result.output().contains("Anthropic"),
        "image rejection must name the Anthropic 5 MB constraint or the downsample remedy: "
            + result.output());
  }

  @Test
  void oversizedPdfAttachmentIsRejectedAtPdfCap(@TempDir Path tmp) throws IOException {
    // PDF just above MAX_PDF_BYTES (20 MB) but below MAX_FILE_SIZE_BYTES (25 MB) — fails at the
    // PDF-specific cap with a message pointing at the file-upload route.
    var file = tmp.resolve("big.pdf");
    try (var out = Files.newOutputStream(file)) {
      out.write("%PDF-1.4\n".getBytes(StandardCharsets.UTF_8));
      out.write(new byte[(int) ReadTool.MAX_PDF_BYTES + 1024]); // 20 MB + 1 KB > 20 MB cap
    }
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "big.pdf"));
    assertFalse(result.success(), result.output());
    assertTrue(result.output().contains("exceeds inline attachment limit"), result.output());
    assertTrue(
        result.output().contains("file-upload"),
        "PDF rejection must point at the file-upload route: " + result.output());
  }

  @Test
  void imageAtImageCapIsAccepted(@TempDir Path tmp) throws IOException {
    // Exactly MAX_IMAGE_BYTES (5 MB) is the boundary — must succeed. Verifies the comparison is
    // strictly greater-than rather than greater-or-equal.
    var file = tmp.resolve("borderline.png");
    var pngHeader = new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    var totalSize = (int) ReadTool.MAX_IMAGE_BYTES;
    var bytes = new byte[totalSize];
    System.arraycopy(pngHeader, 0, bytes, 0, pngHeader.length);
    Files.write(file, bytes);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "borderline.png"));
    assertTrue(result.success(), result.output());
    assertTrue(result.hasAttachments());
  }

  @Test
  void realPngReachesProviderAsInlineFileBytesUnchanged(@TempDir Path tmp) throws IOException {
    // Higher-fidelity than the 8-byte-header tests above: an actual valid 1x1 RGBA PNG (67 bytes
    // including IHDR/IDAT/IEND with correct CRCs). Embedded inline rather than generated via
    // ImageIO so the test stays inside java.base (the session module doesn't read java.desktop).
    // Provider integration is still a manual smoke check; this proves the bytes are pristine
    // from filesystem → ToolResult.attachments — no UTF-8 mangling, no line-ending rewriting,
    // no partial-buffer reads.
    var pngBytes = minimalValid1x1PngBytes();
    Files.write(tmp.resolve("real.png"), pngBytes);

    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "real.png"));
    assertTrue(result.success(), result.output());
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

  /**
   * 67-byte minimal valid 1x1 PNG with correct IHDR / IDAT / IEND chunks and CRC checksums.
   * Decodable by any PNG reader (including the vision channels of Anthropic, Gemini, OpenAI).
   * Inlined here so the test stays in {@code java.base} — the session module doesn't read {@code
   * java.desktop} and the JPMS layer rejects {@code BufferedImage} access at runtime.
   */
  private static byte[] minimalValid1x1PngBytes() {
    return new byte[] {
      // PNG signature
      (byte) 0x89,
      0x50,
      0x4E,
      0x47,
      0x0D,
      0x0A,
      0x1A,
      0x0A,
      // IHDR chunk: length=13, "IHDR", width=1, height=1, bit-depth=8, color-type=0 (grayscale),
      // compression=0, filter=0, interlace=0, CRC
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
      // IDAT chunk: length=10, "IDAT", deflate-compressed single grey pixel, CRC
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
      // IEND chunk: length=0, "IEND", CRC
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

  @Test
  void realPdfDocumentReachesProviderAsInlineFile(@TempDir Path tmp) throws IOException {
    // Hand-rolled minimal PDF — a single-page document with "Hello, world!" content. This is a
    // STRUCTURALLY valid PDF (parseable by PDF readers, accepted by Anthropic / Gemini document
    // channels), not just the %PDF-1.4 magic. Bytes are written verbatim and re-checked at the
    // attachment boundary to prove no UTF-8 or line-ending mangling.
    var pdfBytes = minimalValidPdfBytes();
    Files.write(tmp.resolve("paper.pdf"), pdfBytes);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "paper.pdf"));
    assertTrue(result.success(), result.output());
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

  /**
   * Build a minimal valid PDF in memory: 1 page, "Hello, world!" rendered via the Helvetica
   * standard font. ~500 bytes, parseable by any PDF reader. Used to prove the ReadTool's attachment
   * path doesn't mangle binary content (cross-reference offsets, stream lengths, and the EOF
   * trailer all have to line up byte-for-byte).
   */
  private static byte[] minimalValidPdfBytes() {
    // Use ISO_8859_1 so each char is exactly one byte; PDFs store binary streams as latin-1
    // text and the xref offsets are byte counts.
    var charset = java.nio.charset.StandardCharsets.ISO_8859_1;
    // Compute /Length from the actual stream bytes — getting this wrong silently corrupts the
    // page (a previous version had /Length 51 when the stream was 46 bytes; Gemini accepted the
    // file but extracted no text, then chewed through maxTurns retrying. Real PDF spec violation,
    // real model failure mode).
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
    var out = new java.io.ByteArrayOutputStream();
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
    } catch (java.io.IOException impossible) {
      throw new AssertionError(impossible);
    }
    return out.toByteArray();
  }

  @Test
  void mimeProbeFallsBackToExtensionForCommonTypes(@TempDir Path tmp) throws IOException {
    // Some minimal containers have no system MIME registry — verify the JDK probe fallback
    // path returns text/markdown / application/json without throwing.
    Files.writeString(tmp.resolve("notes.md"), "# title\n", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("data.json"), "{\"x\":1}", StandardCharsets.UTF_8);
    var binding = ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create());

    var md = binding.tool().execute(Map.of("path", "notes.md"));
    assertTrue(md.success(), md.output());
    assertTrue(md.output().contains("title"));

    var json = binding.tool().execute(Map.of("path", "data.json"));
    assertTrue(json.success(), json.output());
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
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "Dockerfile_no_extension"));
    assertTrue(result.success(), result.output());
    assertTrue(result.output().contains("FROM alpine"));
  }

  @Test
  void successResultHasNoAttachmentsForPlainText(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("note.txt"), "hello\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "note.txt"));
    assertTrue(result.success());
    assertFalse(result.hasAttachments(), "text results carry no attachments");
    assertEquals(0, result.attachments().size());
  }

  // ── MIME detection branch coverage ─────────────────────────────────────

  /**
   * Covers every extension branch in {@link ReadTool#detectMimeType}. Each test in this batch
   * writes a tiny file with the named extension and asserts the dispatch happens correctly — text
   * files come back with their content, binary-attachable types come back as attachments,
   * everything else gets the right MIME from the extension fallback. Coverage gate guard.
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
          ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
              .tool()
              .execute(Map.of("path", "file." + ext));
      assertTrue(result.success(), ext + " should be text-readable: " + result.output());
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
          ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
              .tool()
              .execute(Map.of("path", "img." + ext));
      assertTrue(result.success(), ext + ": " + result.output());
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
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "config.unknown"));
    assertTrue(result.success(), result.output());
    assertTrue(result.output().contains("key=value"), result.output());
  }

  @Test
  void emptyFileServedAsTextWithNoOutput(@TempDir Path tmp) throws IOException {
    // Empty file: text path, empty output, no truncation marker. Edge case worth pinning so
    // future refactors don't accidentally emit synthetic markers for the empty case.
    var file = tmp.resolve("empty.txt");
    Files.createFile(file);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "empty.txt"));
    assertTrue(result.success(), result.output());
    assertEquals("", result.output());
  }

  @Test
  void exactLimitNoTruncationMarker(@TempDir Path tmp) throws IOException {
    // Exactly limit lines, exactly EOF — no truncation marker should appear. Boundary test for
    // the limit logic; off-by-one here used to add a spurious marker pre-refactor.
    Files.writeString(tmp.resolve("five.txt"), "a\nb\nc\nd\ne\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "five.txt", "limit", 5));
    assertTrue(result.success());
    assertFalse(
        result.output().contains("[truncated"),
        "reading exactly limit lines should NOT emit a truncation marker: " + result.output());
  }

  @Test
  void publicConstantsHaveTheDocumentedValues() {
    // Pin the public surface so a refactor doesn't silently halve the caps. The split image vs
    // PDF caps are deliberate: 5 MB images matches Anthropic's published per-image floor (the
    // strictest provider); 20 MB PDFs is safe across all three providers' document channels.
    assertEquals(2000, ReadTool.DEFAULT_LIMIT);
    assertEquals(25L * 1024 * 1024, ReadTool.MAX_FILE_SIZE_BYTES);
    assertEquals(1024 * 1024, ReadTool.MAX_LINE_BYTES);
    assertEquals(4 * 1024 * 1024, ReadTool.MAX_OUTPUT_BYTES);
    assertEquals(5L * 1024 * 1024, ReadTool.MAX_IMAGE_BYTES);
    assertEquals(20L * 1024 * 1024, ReadTool.MAX_PDF_BYTES);
  }
}
