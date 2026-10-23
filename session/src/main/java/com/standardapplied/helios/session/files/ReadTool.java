/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import com.standardapplied.helios.core.common.Result;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.InlineFile;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.tools.ToolArgs;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
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
 *   <li>{@code path} (required) — workspace-relative or absolute path. The session authorises the
 *       call on its canonical spelling ({@code ./a/b}) and runs the tool on that spelling, which
 *       {@link WorkspaceRoot#resolveCanonical(String)} refuses if it no longer names the target
 *       that was authorised.
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
 *   <li>{@link #MAX_LINE_CHARS} on each emitted line — a single-line minified JSON is cut with a
 *       marker rather than blowing the model's context.
 *   <li>{@link #MAX_OUTPUT_CHARS} on the page — many short lines stop at a page that fits the
 *       session's tool-result cap.
 * </ul>
 *
 * Text rendering streams through {@link java.io.BufferedReader} and stops at the first output cap
 * hit; fingerprinting separately reads the bounded source. The truncation marker names the {@code
 * offset} to continue from. {@code offset} and {@code limit} are validated before the file is read.
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
 *       PDF channel handles them, capped at {@link #MAX_IMAGE_BYTES} and {@link #MAX_PDF_BYTES}.
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
   * Maximum source-file size the text path reads. It bounds that read, not a provider request: the
   * page sent is capped by {@link #MAX_OUTPUT_CHARS}, and attachments by {@link #MAX_IMAGE_BYTES}
   * and {@link #MAX_PDF_BYTES}.
   */
  public static final long MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024;

  /**
   * Maximum chars of a single emitted text line; a longer line is cut with a marker. It stays far
   * below {@link #MAX_OUTPUT_CHARS}, so every page holds at least one line and paging advances.
   */
  public static final int MAX_LINE_CHARS = 2000;

  /**
   * Maximum chars of a page of text output, summed across lines. A page always fits under the
   * session's tool-result cap, so it keeps its {@code offset} continuation hint.
   */
  public static final int MAX_OUTPUT_CHARS = 60_000;

  /**
   * Maximum size of a single image attachment. Base64-encoded it is 10,000,000 bytes, Anthropic's
   * per-image limit (<a href="https://platform.claude.com/docs/en/build-with-claude/vision">Vision,
   * "Request limits"</a>).
   */
  public static final long MAX_IMAGE_BYTES = 7_500_000;

  /**
   * Maximum size of a single PDF attachment. Base64-encoded it is 24,000,000 bytes, leaving
   * 8,000,000 bytes of Anthropic's 32 MB request for the rest of the conversation (<a
   * href="https://platform.claude.com/docs/en/build-with-claude/pdf-support">PDF support</a>).
   */
  public static final long MAX_PDF_BYTES = 18_000_000;

  private ReadTool() {}

  /**
   * Build a tool binding bound to the given workspace + tracker. The session redacts the text the
   * tool returns against its secret registry; attachments (images, PDFs) pass through unredacted. A
   * line cut at {@link #MAX_LINE_CHARS} loses the start of a registered secret the cut went
   * through, so the session's redaction cannot miss it.
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
        .withPathArgument(ToolBinding.PathArgument.required("path", workspace))
        .build();
  }

  private static ToolResult execute(
      ToolContext ctx, WorkspaceRoot workspace, FileTracker tracker, Map<String, Object> args) {
    ctx.cancellation().throwIfCancelled();
    return switch (ReadRequest.parse(args)) {
      case Result.Failure<ReadRequest> failure -> ToolResult.failure(failure.error());
      case Result.Success<ReadRequest> request ->
          resolve(workspace, tracker, ctx.sessionContext().secrets(), request.value());
    };
  }

  private static ToolResult resolve(
      WorkspaceRoot workspace, FileTracker tracker, SecretRegistry secrets, ReadRequest request) {
    Path resolved;
    try {
      resolved = workspace.resolveCanonical(request.path());
    } catch (WorkspaceRoot.WorkspaceEscapeException e) {
      return ToolResult.failure("Read: " + e.getMessage());
    }
    return switch (regularFileSize(workspace, resolved)) {
      case Result.Failure<Long> failure -> ToolResult.failure(failure.error());
      case Result.Success<Long> size ->
          read(workspace, tracker, secrets, request, resolved, size.value());
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
      SecretRegistry secrets,
      ReadRequest request,
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
          : TextPage.read(workspace, resolved, request.offset(), request.limit(), secrets);
    }
    if (MimeTypes.isLikelyText(workspace, resolved, MAX_FILE_SIZE_BYTES)) {
      return TextPage.read(workspace, resolved, request.offset(), request.limit(), secrets);
    }
    return ToolResult.failure(
        "Read: refusing to decode binary file as text (detected MIME unknown). Images and PDFs"
            + " are returned as attachments; for other binary formats use a dedicated tool or"
            + " extract the payload server-side before passing the bytes through.");
  }

  /**
   * Return the file as an {@link InlineFile} attachment so the provider's vision / PDF channel
   * consumes it natively. Images apply {@link #MAX_IMAGE_BYTES}, PDFs {@link #MAX_PDF_BYTES}.
   * Rejection produces a clean message naming the limit and what to do next, rather than racing the
   * API to a cryptic provider 413.
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
              + "). The file is resent with every turn; route a file this large through a"
              + " file-upload host tool.");
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

  private record ReadRequest(String path, int offset, int limit) {

    static Result<ReadRequest> parse(Map<String, Object> args) {
      var path = ToolArgs.stringArg(args, "path");
      if (path.isEmpty()) {
        return new Result.Failure<>("Read: missing required 'path' argument");
      }
      var offset = ToolArgs.intArg(args, "offset", 1);
      var limit = ToolArgs.intArg(args, "limit", DEFAULT_LIMIT);
      if (offset < 1) {
        return new Result.Failure<>("Read: 'offset' must be >= 1, got " + offset);
      }
      if (limit < 1) {
        return new Result.Failure<>("Read: 'limit' must be >= 1, got " + limit);
      }
      return new Result.Success<>(new ReadRequest(path, offset, limit));
    }
  }
}
