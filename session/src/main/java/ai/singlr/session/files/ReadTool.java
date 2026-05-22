/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import ai.singlr.core.model.InlineFile;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolContext;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.tools.ToolArgs;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolCategory;
import ai.singlr.session.tools.ToolPermissionKey;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Built-in {@code Read} tool. Mirrors the Claude Code Read tool's contract: text files come back
 * line-numbered and bounded, images and PDFs come back as multimodal attachments the provider's
 * vision channel consumes natively, oversized or binary-of-unknown-type files fail fast with a
 * message that teaches the model what to do instead.
 *
 * <h2>Arguments</h2>
 *
 * <ul>
 *   <li>{@code path} (required) — workspace-relative or absolute path.
 *   <li>{@code offset} (optional integer; default 1) — 1-based line at which text output begins.
 *       Ignored for image / PDF attachments.
 *   <li>{@code limit} (optional integer; default {@value #DEFAULT_LIMIT}) — maximum number of lines
 *       emitted from text files.
 * </ul>
 *
 * <h2>Bounded output</h2>
 *
 * Text reading is bounded by <b>three</b> independent caps:
 *
 * <ul>
 *   <li>{@link #MAX_FILE_SIZE_BYTES} on the source file — pre-checked via {@link Files#size} before
 *       any I/O. A 500 MB log fails before allocating a buffer.
 *   <li>{@link #MAX_LINE_BYTES} on each emitted line — a 100 MB single-line JSON gets truncated
 *       with a marker rather than blowing the model's context.
 *   <li>{@link #MAX_OUTPUT_BYTES} on the total output payload — the line cap is a ceiling, not a
 *       guarantee against pathological per-line growth.
 * </ul>
 *
 * The reader streams the file via {@link BufferedReader} and stops at the first cap hit; the rest
 * of the file is never touched. The truncation marker teaches the model what to try next ("use
 * {@code offset} to continue, or {@code Grep} for a narrower target").
 *
 * <h2>Multimodal dispatch</h2>
 *
 * {@link Files#probeContentType} drives a three-way dispatch:
 *
 * <ul>
 *   <li>Text-like MIME ({@code text/*}, {@code application/json}, {@code application/xml}, {@code
 *       application/yaml}, {@code application/x-yaml}) or no detected MIME with a clean NUL-free
 *       header → bounded text path above.
 *   <li>{@code image/*} or {@code application/pdf} → {@link ToolResult#successWithAttachments
 *       attachment path}: the bytes ride as an {@link InlineFile} so the provider's native vision /
 *       PDF channel handles them. Images are capped at {@link #MAX_IMAGE_BYTES} (Anthropic's 5 MB
 *       per-image floor); PDFs are capped at the looser {@link #MAX_PDF_BYTES}.
 *   <li>Any other binary (NUL byte in the first 8 KB and no recognised MIME) → fail fast with a
 *       message naming the detected type.
 * </ul>
 *
 * <p>The output format for text matches Claude Code's Read: 6-digit right-padded line numbers
 * separated by a tab. Edits made by downstream edit tools can detect stale reads via {@link
 * FileTracker}'s fingerprint check.
 */
public final class ReadTool {

  /** The stable tool name advertised to the model. */
  public static final String NAME = "Read";

  /** Default cap on the number of lines emitted from a text file. Matches Claude Code's Read. */
  public static final int DEFAULT_LIMIT = 2000;

  /**
   * Maximum source-file size accepted by the text path. 25 MB stays inside every supported
   * provider's per-request limit (Anthropic 32 MB total request, Gemini 50 MB inline) with margin
   * for base64 overhead and the assistant's own response. Attachment paths apply their own tighter
   * caps; see {@link #MAX_IMAGE_BYTES} and {@link #MAX_PDF_BYTES}.
   */
  public static final long MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024;

  /**
   * Maximum cap on a single emitted text line. A 1 MB minified-JSON-on-one-line file truncates to 1
   * MB on that line rather than streaming the entire blob into the model context.
   */
  public static final int MAX_LINE_BYTES = 1024 * 1024;

  /**
   * Maximum cap on the total bytes of text output, summed across lines. Stops a degenerate file
   * (millions of short lines) from blowing context even when each individual line is small.
   */
  public static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;

  /**
   * Maximum cap on a single image attachment. 5 MB matches Anthropic's published per-image limit
   * (verified against {@code platform.claude.com/docs/en/build-with-claude/vision}), which is the
   * strictest of the three providers Helios talks to — Gemini and OpenAI permit larger payloads but
   * failing here with a clear message is better than racing the API to a cryptic 400. Deployers
   * locked to a single looser-limit provider can fork {@link #binding} with an explicit override
   * (the surface is a static field on purpose, mechanical to subclass).
   */
  public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

  /**
   * Maximum cap on a single PDF attachment. 20 MB stays inside every published provider PDF inline
   * limit (Anthropic 32 MB request budget; Gemini 50 MB per document). PDFs travel through the
   * document content channel separate from images, so the higher cap is safe and useful — most
   * agent workloads land here for technical papers and reports that exceed the image limit.
   */
  public static final long MAX_PDF_BYTES = 20L * 1024 * 1024;

  /** Number of bytes sniffed when detecting whether an unknown-MIME file is binary. */
  static final int BINARY_SNIFF_BYTES = 8 * 1024;

  private ReadTool() {}

  /**
   * Build a tool binding bound to the given workspace + tracker.
   *
   * @param workspace the path-jail workspace; non-null
   * @param tracker per-session read/write ledger; non-null
   * @return a ready-to-register binding
   * @throws NullPointerException if either argument is null
   */
  public static ToolBinding binding(WorkspaceRoot workspace, FileTracker tracker) {
    Objects.requireNonNull(workspace, "workspace must not be null");
    Objects.requireNonNull(tracker, "tracker must not be null");
    var tool =
        Tool.newBuilder()
            .withName(NAME)
            .withDescription(
                "Reads a file from the workspace. Text files come back line-numbered and "
                    + "bounded (default 2000 lines); images and PDFs come back as attachments "
                    + "the model sees natively. Use 'offset' (1-based) and 'limit' to page "
                    + "through large text files; truncated output names exactly what to try next.")
            .withParameters(
                List.of(
                    ToolParameter.newBuilder()
                        .withName("path")
                        .withType(ParameterType.STRING)
                        .withDescription("Workspace-relative or absolute path to the file.")
                        .withRequired(true)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("offset")
                        .withType(ParameterType.INTEGER)
                        .withDescription(
                            "1-based line at which output begins. Defaults to 1. Text only.")
                        .withRequired(false)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("limit")
                        .withType(ParameterType.INTEGER)
                        .withDescription(
                            "Maximum number of lines emitted from text files. Defaults to "
                                + DEFAULT_LIMIT
                                + ".")
                        .withRequired(false)
                        .build()))
            .withIdempotent(true)
            .withExecutor((args, ctx) -> execute(ctx, workspace, tracker, args))
            .build();
    return ToolBinding.newBuilder(tool)
        .withCategory(ToolCategory.READ)
        .withPermissionKeyExtractor(
            args -> new ToolPermissionKey(NAME, ToolArgs.stringArg(args, "path")))
        .build();
  }

  private static ToolResult execute(
      ToolContext ctx, WorkspaceRoot workspace, FileTracker tracker, Map<String, Object> args) {
    ctx.cancellation().throwIfCancelled();
    var pathArg = ToolArgs.stringArg(args, "path");
    if (pathArg.isEmpty()) {
      return ToolResult.failure("Read: missing required 'path' argument");
    }
    Path resolved;
    try {
      resolved = workspace.resolveSafe(pathArg);
    } catch (WorkspaceRoot.WorkspaceEscapeException e) {
      return ToolResult.failure("Read: " + e.getMessage());
    }
    if (!Files.isRegularFile(resolved)) {
      return ToolResult.failure("Read: not a regular file: " + workspace.relativize(resolved));
    }
    long size;
    try {
      size = Files.size(resolved);
    } catch (IOException e) {
      return ToolResult.failure("Read: I/O error reading size: " + e.getMessage());
    }
    if (size > MAX_FILE_SIZE_BYTES) {
      return ToolResult.failure(
          "Read: file exceeds maximum size of "
              + MAX_FILE_SIZE_BYTES
              + " bytes (was "
              + size
              + "). Use a Grep over the relevant pattern or split the file before reading.");
    }
    try {
      var fingerprint = FileFingerprint.of(resolved);
      tracker.recordRead(resolved, fingerprint);
    } catch (IOException e) {
      return ToolResult.failure("Read: I/O error fingerprinting: " + e.getMessage());
    }

    var mimeType = detectMimeType(resolved);
    if (isAttachableBinary(mimeType)) {
      return readBinaryAsAttachment(resolved, mimeType, size, workspace.relativize(resolved));
    }
    if (isTextLike(mimeType) || isLikelyText(resolved)) {
      var offset = ToolArgs.intArg(args, "offset", 1);
      var limit = ToolArgs.intArg(args, "limit", DEFAULT_LIMIT);
      if (offset < 1) {
        return ToolResult.failure("Read: 'offset' must be >= 1, got " + offset);
      }
      if (limit < 1) {
        return ToolResult.failure("Read: 'limit' must be >= 1, got " + limit);
      }
      return readTextStreaming(resolved, offset, limit);
    }
    return ToolResult.failure(
        "Read: refusing to decode binary file as text (detected MIME "
            + (mimeType == null ? "unknown" : mimeType)
            + "). Images and PDFs are returned as attachments; for other binary formats use a "
            + "dedicated tool or extract the payload server-side before passing the bytes through.");
  }

  /**
   * Stream the file line by line, emitting at most {@code limit} lines starting at {@code offset}.
   * Each line is capped at {@link #MAX_LINE_BYTES} and the total output at {@link
   * #MAX_OUTPUT_BYTES}; either cap appends a truncation marker that teaches the model the next
   * move. The remainder of the file is never read once a cap fires.
   */
  private static ToolResult readTextStreaming(Path file, int offset, int limit) {
    var out = new StringBuilder();
    int linesEmitted = 0;
    long currentLine = 0;
    boolean truncatedByLines = false;
    boolean truncatedByBytes = false;
    boolean truncatedAnyLine = false;
    try (var reader =
        new BufferedReader(
            new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        currentLine++;
        if (currentLine < offset) {
          continue;
        }
        if (linesEmitted >= limit) {
          truncatedByLines = true;
          break;
        }
        var lineForOutput = line;
        if (lineForOutput.length() > MAX_LINE_BYTES) {
          lineForOutput =
              lineForOutput.substring(0, MAX_LINE_BYTES)
                  + " [line truncated to "
                  + MAX_LINE_BYTES
                  + " bytes]";
          truncatedAnyLine = true;
        }
        var entry = String.format("%6d\t%s%n", currentLine, lineForOutput);
        if (out.length() + entry.length() > MAX_OUTPUT_BYTES) {
          truncatedByBytes = true;
          break;
        }
        out.append(entry);
        linesEmitted++;
      }
    } catch (IOException e) {
      return ToolResult.failure("Read: I/O error reading file: " + e.getMessage());
    }
    if (truncatedByLines) {
      out.append("[truncated at line ")
          .append(currentLine - 1)
          .append("; ")
          .append(linesEmitted)
          .append(" lines emitted. Use offset=")
          .append(currentLine)
          .append(" to continue, or Grep for a narrower target.]\n");
    } else if (truncatedByBytes) {
      out.append("[truncated: total output exceeded ")
          .append(MAX_OUTPUT_BYTES)
          .append(" bytes after ")
          .append(linesEmitted)
          .append(" lines. Use Grep to locate the section you need.]\n");
    } else if (truncatedAnyLine) {
      out.append("[note: at least one line exceeded ")
          .append(MAX_LINE_BYTES)
          .append(" bytes and was truncated mid-line.]\n");
    }
    return ToolResult.success(out.toString());
  }

  /**
   * Return the file as an {@link InlineFile} attachment so the provider's vision / PDF channel
   * consumes it natively. Images apply {@link #MAX_IMAGE_BYTES} (Anthropic floor at 5 MB); PDFs
   * apply the looser {@link #MAX_PDF_BYTES}. Rejection produces a clean message naming the limit
   * and what to do next, rather than racing the API to a cryptic provider 400.
   */
  private static ToolResult readBinaryAsAttachment(
      Path file, String mimeType, long size, String relPath) {
    var limit = mimeType.startsWith("image/") ? MAX_IMAGE_BYTES : MAX_PDF_BYTES;
    if (size > limit) {
      return ToolResult.failure(
          "Read: "
              + mimeType
              + " file '"
              + relPath
              + "' exceeds inline attachment limit of "
              + limit
              + " bytes (was "
              + size
              + "). "
              + (mimeType.startsWith("image/")
                  ? "Anthropic caps inline images at 5 MB; downsample / re-encode before "
                      + "reading, or route through a file-upload host tool."
                  : "Route through a file-upload host tool for PDFs this large."));
    }
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(file);
    } catch (IOException e) {
      return ToolResult.failure("Read: I/O error reading attachment bytes: " + e.getMessage());
    }
    var note =
        "Returned "
            + mimeType
            + " file '"
            + relPath
            + "' as a multimodal attachment ("
            + size
            + " bytes). Inspect the attached content directly.";
    return ToolResult.successWithAttachments(note, List.of(InlineFile.of(bytes, mimeType)));
  }

  /**
   * Probe the file's MIME type. Falls back to extension sniffing when {@link
   * Files#probeContentType} returns null — the JDK's probe relies on the host platform's registry,
   * which can be sparse on minimal containers. Returns {@code null} when nothing recognises the
   * file.
   */
  static String detectMimeType(Path file) {
    try {
      var probed = Files.probeContentType(file);
      if (probed != null) {
        return probed;
      }
    } catch (IOException ignored) {
      // probeContentType is best-effort; fall through to extension sniffing.
    }
    var name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    return switch (extensionOf(name)) {
      case "pdf" -> "application/pdf";
      case "png" -> "image/png";
      case "jpg", "jpeg" -> "image/jpeg";
      case "gif" -> "image/gif";
      case "webp" -> "image/webp";
      case "json" -> "application/json";
      case "xml" -> "application/xml";
      case "yaml", "yml" -> "application/yaml";
      case "html", "htm" -> "text/html";
      case "css" -> "text/css";
      case "js", "mjs" -> "text/javascript";
      case "java", "kt", "scala", "py", "rb", "go", "rs", "c", "cpp", "h", "hpp", "ts", "tsx" ->
          "text/plain";
      case "md", "markdown" -> "text/markdown";
      case "csv", "tsv" -> "text/plain";
      case "log", "txt" -> "text/plain";
      default -> null;
    };
  }

  private static String extensionOf(String name) {
    var dot = name.lastIndexOf('.');
    return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "";
  }

  static boolean isAttachableBinary(String mimeType) {
    if (mimeType == null) {
      return false;
    }
    return mimeType.startsWith("image/") || "application/pdf".equals(mimeType);
  }

  static boolean isTextLike(String mimeType) {
    if (mimeType == null) {
      return false;
    }
    if (mimeType.startsWith("text/")) {
      return true;
    }
    return switch (mimeType) {
      case "application/json",
          "application/xml",
          "application/yaml",
          "application/x-yaml",
          "application/javascript" ->
          true;
      default -> false;
    };
  }

  /**
   * Sniff the first {@link #BINARY_SNIFF_BYTES} bytes for NUL — present in essentially every binary
   * format, absent in real-world text. Used only when the MIME probe came back null; lets us still
   * serve uncategorised-but-clearly-text files (e.g. config files without standard extensions) as
   * text rather than failing.
   */
  static boolean isLikelyText(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      var buf = new byte[BINARY_SNIFF_BYTES];
      var n = in.readNBytes(buf, 0, buf.length);
      for (var i = 0; i < n; i++) {
        if (buf[i] == 0) {
          return false;
        }
      }
      return true;
    } catch (IOException ignored) {
      return false;
    }
  }
}
