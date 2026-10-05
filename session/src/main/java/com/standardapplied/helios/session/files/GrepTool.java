/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import com.standardapplied.helios.core.common.Redactor;
import com.standardapplied.helios.core.common.Result;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.tools.ToolArgs;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Built-in {@code Grep} tool. Searches workspace files for a Java regex pattern and returns
 * matching lines prefixed with {@code path:lineNumber:line}.
 *
 * <p>Arguments:
 *
 * <ul>
 *   <li>{@code pattern} (required) — Java regex pattern. Anchors and character classes work as in
 *       {@link Pattern}.
 *   <li>{@code path} (optional, default {@code "."}) — root directory to scan.
 *   <li>{@code include} (optional) — additional glob filter applied to candidate filenames before
 *       searching (e.g. {@code "*.java"}). Defaults to "all text files".
 * </ul>
 *
 * <p>Bounds:
 *
 * <ul>
 *   <li>Per-file size cap: {@code 1 MiB}. Files larger than this are skipped.
 *   <li>Total result cap: {@code 1000} match lines.
 *   <li>Binary detection: files with a NUL byte in the first 8 KiB are skipped.
 *   <li>Hidden directories ({@code ".git"} etc.) are pruned during traversal.
 *   <li>Only regular files are opened: symlinks, FIFOs and device files discovered during the walk
 *       are skipped, and every open is no-follow via {@link WorkspaceRoot#newInputStream(Path)}.
 * </ul>
 */
public final class GrepTool {

  /** The stable tool name advertised to the model. */
  public static final String NAME = "Grep";

  private static final int MAX_MATCHES = 1000;
  private static final long MAX_FILE_BYTES = 1L * 1024 * 1024;
  private static final int BINARY_SNIFF_BYTES = 8 * 1024;

  private GrepTool() {}

  /**
   * Build a tool binding bound to the given workspace, with no secret redaction. Equivalent to
   * {@link #binding(WorkspaceRoot, Redactor) binding(workspace, null)}.
   *
   * @param workspace the path-jail workspace; non-null
   * @return a ready-to-register binding
   * @throws NullPointerException if {@code workspace} is null
   */
  public static ToolBinding binding(WorkspaceRoot workspace) {
    return binding(workspace, null);
  }

  /**
   * Build a tool binding bound to the given workspace, piping the matched line content through the
   * supplied {@link Redactor} before returning it to the model. Use this overload when the searched
   * tree may contain registered secrets (the "curated knowledge corpus" pattern) — pass {@code
   * registry.redactor()} where {@code registry} is the same {@link
   * com.standardapplied.helios.core.common.SecretRegistry} you handed to other tools, so a token
   * written by one tool is scrubbed when grep returns a line containing it.
   *
   * <p>Redaction is applied to the {@code content} portion of each {@code path:line:content} match
   * only. Path prefixes are not redacted — they are structural information the model needs to
   * navigate, not secret material.
   *
   * @param workspace the path-jail workspace; non-null
   * @param redactor applied to each match's content; null = no redaction
   * @return a ready-to-register binding
   * @throws NullPointerException if {@code workspace} is null
   */
  public static ToolBinding binding(WorkspaceRoot workspace, Redactor redactor) {
    Objects.requireNonNull(workspace, "workspace must not be null");
    var tool =
        Tool.newBuilder()
            .withName(NAME)
            .withDescription(
                "Searches files for a Java regex pattern. Returns 'path:line:content' "
                    + "lines, capped at "
                    + MAX_MATCHES
                    + ". Binary and large files are skipped.")
            .withParameters(
                List.of(
                    ToolParameter.newBuilder()
                        .withName("pattern")
                        .withType(ParameterType.STRING)
                        .withDescription("Java regex pattern to search for.")
                        .withRequired(true)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("path")
                        .withType(ParameterType.STRING)
                        .withDescription(
                            "Root directory to scan, workspace-relative or absolute. Defaults to '.'.")
                        .withRequired(false)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("include")
                        .withType(ParameterType.STRING)
                        .withDescription(
                            "Optional filename glob filter, e.g. '*.java'. Defaults to all files.")
                        .withRequired(false)
                        .build()))
            .withIdempotent(true)
            .withExecutor((args, ctx) -> execute(ctx, workspace, redactor, args))
            .build();
    return ToolBinding.newBuilder(tool)
        .withCategory(ToolCategory.SEARCH)
        .withPermissionKeyExtractor(args -> new ToolPermissionKey(NAME, ToolArgs.pathArg(args)))
        .build();
  }

  private static ToolResult execute(
      ToolContext ctx, WorkspaceRoot workspace, Redactor redactor, Map<String, Object> args) {
    return switch (GrepRequest.parse(args)) {
      case Result.Failure<GrepRequest> failure -> ToolResult.failure(failure.error());
      case Result.Success<GrepRequest> request -> grep(ctx, workspace, redactor, request.value());
    };
  }

  private static ToolResult grep(
      ToolContext ctx, WorkspaceRoot workspace, Redactor redactor, GrepRequest request) {
    try {
      var root = workspace.resolveSafe(request.path());
      if (!workspace.attributes(root).isDirectory()) {
        return ToolResult.failure("Grep: not a directory: " + workspace.relativize(root));
      }
      var include = request.includeMatcher(root.getFileSystem());
      var hits = new ArrayList<Hit>();
      var count =
          WorkspaceWalk.run(
              workspace,
              root,
              ctx.cancellation(),
              MAX_MATCHES,
              MAX_FILE_BYTES,
              (file, attrs, remaining) ->
                  include.matches(file.getFileName())
                      ? searchFile(workspace, file, request.regex(), remaining, hits)
                      : 0);
      return ToolResult.success(format(hits, count >= MAX_MATCHES, redactor));
    } catch (WorkspaceRoot.WorkspaceEscapeException e) {
      return ToolResult.failure("Grep: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      return ToolResult.failure(
          "Grep: invalid include pattern '" + request.include() + "': " + e.getMessage());
    } catch (IOException e) {
      return ToolResult.failure(
          "Grep: I/O error scanning " + request.path() + ": " + e.getMessage());
    }
  }

  private static int searchFile(
      WorkspaceRoot workspace, Path file, Pattern regex, int remaining, List<Hit> hits) {
    byte[] bytes;
    try (var in = workspace.newInputStream(file, MAX_FILE_BYTES)) {
      bytes = in.readAllBytes();
    } catch (IOException unreadable) {
      return 0;
    }
    if (isBinary(bytes)) {
      return 0;
    }
    var path = workspace.relativize(file);
    var found = 0;
    try (var reader =
        new BufferedReader(
            new InputStreamReader(
                new ByteArrayInputStream(bytes), StandardCharsets.UTF_8.newDecoder()))) {
      var lineNumber = 0;
      String line;
      while (found < remaining && (line = reader.readLine()) != null) {
        lineNumber++;
        if (regex.matcher(line).find()) {
          hits.add(new Hit(path, lineNumber, line));
          found++;
        }
      }
    } catch (IOException notUtf8) {
      return found;
    }
    return found;
  }

  private static String format(List<Hit> hits, boolean truncated, Redactor redactor) {
    var out = new StringBuilder();
    for (var hit : hits) {
      var line = redactor == null ? hit.line() : redactor.redact(hit.line()).text();
      out.append(hit.path()).append(':').append(hit.lineNumber()).append(':').append(line);
      out.append('\n');
    }
    if (truncated) {
      out.append("[truncated at ").append(MAX_MATCHES).append(" matches]\n");
    }
    return out.toString();
  }

  private static boolean isBinary(byte[] bytes) {
    for (var i = 0; i < Math.min(bytes.length, BINARY_SNIFF_BYTES); i++) {
      if (bytes[i] == 0) {
        return true;
      }
    }
    return false;
  }

  private record GrepRequest(Pattern regex, String path, String include) {

    static Result<GrepRequest> parse(Map<String, Object> args) {
      var pattern = ToolArgs.stringArg(args, "pattern");
      if (Strings.isBlank(pattern)) {
        return new Result.Failure<>("Grep: missing required 'pattern' argument");
      }
      try {
        return new Result.Success<>(
            new GrepRequest(
                Pattern.compile(pattern),
                ToolArgs.pathArg(args),
                ToolArgs.stringArg(args, "include")));
      } catch (PatternSyntaxException e) {
        return new Result.Failure<>("Grep: invalid regex '" + pattern + "': " + e.getDescription());
      }
    }

    PathMatcher includeMatcher(FileSystem fs) {
      return include.isEmpty() ? name -> true : GlobMatchers.compile(fs, include);
    }
  }

  private record Hit(String path, int lineNumber, String line) {}
}
