/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import com.standardapplied.helios.core.common.Redactor;
import com.standardapplied.helios.core.common.Result;
import com.standardapplied.helios.core.model.InlineFile;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.tools.ToolArgs;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
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
 *   <li>{@link #MAX_FILE_SIZE_BYTES} on the source file — pre-checked from the leaf's own
 *       (no-follow) attributes before any I/O. A 500 MB log fails before allocating a buffer.
 *   <li>{@link #MAX_LINE_BYTES} on each emitted line — a 100 MB single-line JSON gets truncated
 *       with a marker rather than blowing the model's context.
 *   <li>{@link #MAX_OUTPUT_BYTES} on the total output payload — the line cap is a ceiling, not a
 *       guarantee against pathological per-line growth.
 * </ul>
 *
 * Text rendering streams through {@link java.io.BufferedReader} and stops at the first output cap
 * hit; fingerprinting separately reads the bounded source. The truncation marker explains what to
 * try next ("use {@code offset} to continue, or {@code Grep} for a narrower target").
 *
 * <h2>Multimodal dispatch</h2>
 *
 * Extension-based MIME classification and a bounded header sniff drive a three-way dispatch:
 *
 * <ul>
 *   <li>Text-like MIME ({@code text/*}, {@code application/json}, {@code application/xml}, {@code
 *       application/yaml}) or no detected MIME with a clean NUL-free header → bounded text path
 *       above.
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

  private ReadTool() {}

  /**
   * Build a tool binding bound to the given workspace + tracker, with no secret redaction.
   * Equivalent to {@link #binding(WorkspaceRoot, FileTracker, Redactor) binding(workspace, tracker,
   * null)}.
   *
   * @param workspace the path-jail workspace; non-null
   * @param tracker per-session read/write ledger; non-null
   * @return a ready-to-register binding
   * @throws NullPointerException if either argument is null
   */
  public static ToolBinding binding(WorkspaceRoot workspace, FileTracker tracker) {
    return binding(workspace, tracker, null);
  }

  /**
   * Build a tool binding bound to the given workspace + tracker, piping text-path output through
   * the supplied {@link Redactor} before returning it to the model. Use this overload when wiring
   * the tool against a corpus that may contain registered secrets (the "curated knowledge corpus"
   * pattern) — pass {@code registry.redactor()} where {@code registry} is the same {@link
   * com.standardapplied.helios.core.common.SecretRegistry} you handed to {@code CommandGrant}, so a
   * token written by one tool is scrubbed when another reads it back.
   *
   * <p>Redaction applies to text-body output only. Error messages, attachment notes, and truncation
   * markers (which may contain workspace-relative paths) are not redacted — paths are structural
   * information the model needs to navigate, not secret material.
   *
   * @param workspace the path-jail workspace; non-null
   * @param tracker per-session read/write ledger; non-null
   * @param redactor applied to text-body output before it reaches the model; null = no redaction
   * @return a ready-to-register binding
   * @throws NullPointerException if {@code workspace} or {@code tracker} is null
   */
  public static ToolBinding binding(
      WorkspaceRoot workspace, FileTracker tracker, Redactor redactor) {
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
            .withExecutor((args, ctx) -> execute(ctx, workspace, tracker, redactor, args))
            .build();
    return ToolBinding.newBuilder(tool)
        .withCategory(ToolCategory.READ)
        .withPermissionKeyExtractor(
            args -> new ToolPermissionKey(NAME, ToolArgs.stringArg(args, "path")))
        .build();
  }

  private static ToolResult execute(
      ToolContext ctx,
      WorkspaceRoot workspace,
      FileTracker tracker,
      Redactor redactor,
      Map<String, Object> args) {
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
    return switch (regularFileSize(workspace, resolved)) {
      case Result.Failure<Long> failure -> ToolResult.failure(failure.error());
      case Result.Success<Long> size ->
          read(workspace, tracker, redactor, args, resolved, size.value());
    };
  }

  private static Result<Long> regularFileSize(WorkspaceRoot workspace, Path resolved) {
    try {
      var attrs = workspace.attributes(resolved);
      if (attrs.isRegularFile()) {
        return new Result.Success<>(attrs.size());
      }
    } catch (NoSuchFileException e) {
      return new Result.Failure<>("Read: not a regular file: " + workspace.relativize(resolved));
    } catch (IOException e) {
      return new Result.Failure<>("Read: I/O error reading size: " + e.getMessage());
    }
    return new Result.Failure<>("Read: not a regular file: " + workspace.relativize(resolved));
  }

  private static ToolResult read(
      WorkspaceRoot workspace,
      FileTracker tracker,
      Redactor redactor,
      Map<String, Object> args,
      Path resolved,
      long size) {
    if (size > MAX_FILE_SIZE_BYTES) {
      return ToolResult.failure(
          "Read: file exceeds maximum size of "
              + MAX_FILE_SIZE_BYTES
              + " bytes (was "
              + size
              + "). Use a Grep over the relevant pattern or split the file before reading.");
    }
    try {
      var fingerprint = FileFingerprint.of(workspace, resolved, MAX_FILE_SIZE_BYTES);
      tracker.recordRead(resolved, fingerprint);
    } catch (IOException e) {
      return ToolResult.failure("Read: I/O error fingerprinting: " + e.getMessage());
    }
    var mimeType = MimeTypes.detect(resolved);
    if (mimeType.isPresent()) {
      return mimeType.get().channel() == MimeTypes.Channel.ATTACHMENT
          ? readAttachment(workspace, resolved, mimeType.get().name(), size)
          : readText(workspace, resolved, args, redactor);
    }
    if (MimeTypes.isLikelyText(workspace, resolved, MAX_FILE_SIZE_BYTES)) {
      return readText(workspace, resolved, args, redactor);
    }
    return ToolResult.failure(
        "Read: refusing to decode binary file as text (detected MIME unknown). Images and PDFs"
            + " are returned as attachments; for other binary formats use a dedicated tool or"
            + " extract the payload server-side before passing the bytes through.");
  }

  private static ToolResult readText(
      WorkspaceRoot workspace, Path file, Map<String, Object> args, Redactor redactor) {
    var offset = ToolArgs.intArg(args, "offset", 1);
    var limit = ToolArgs.intArg(args, "limit", DEFAULT_LIMIT);
    if (offset < 1) {
      return ToolResult.failure("Read: 'offset' must be >= 1, got " + offset);
    }
    if (limit < 1) {
      return ToolResult.failure("Read: 'limit' must be >= 1, got " + limit);
    }
    return TextPage.read(workspace, file, offset, limit, redactor);
  }

  /**
   * Return the file as an {@link InlineFile} attachment so the provider's vision / PDF channel
   * consumes it natively. Images apply {@link #MAX_IMAGE_BYTES} (Anthropic floor at 5 MB); PDFs
   * apply the looser {@link #MAX_PDF_BYTES}. Rejection produces a clean message naming the limit
   * and what to do next, rather than racing the API to a cryptic provider 400.
   */
  private static ToolResult readAttachment(
      WorkspaceRoot workspace, Path file, String mimeType, long size) {
    var relPath = workspace.relativize(file);
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
      try (var in = workspace.newInputStream(file, limit)) {
        bytes = in.readAllBytes();
      }
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
}
