/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import ai.singlr.core.common.Redactor;
import ai.singlr.core.common.Strings;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolContext;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.tools.ToolArgs;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolCategory;
import ai.singlr.session.tools.ToolPermissionKey;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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
   * ai.singlr.core.common.SecretRegistry} you handed to other tools, so a token written by one tool
   * is scrubbed when grep returns a line containing it.
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
    var rawPattern = ToolArgs.stringArg(args, "pattern");
    if (Strings.isBlank(rawPattern)) {
      return ToolResult.failure("Grep: missing required 'pattern' argument");
    }
    Pattern regex;
    try {
      regex = Pattern.compile(rawPattern);
    } catch (PatternSyntaxException e) {
      return ToolResult.failure("Grep: invalid regex '" + rawPattern + "': " + e.getDescription());
    }
    var pathArg = ToolArgs.pathArg(args);
    var includeArg = ToolArgs.stringArg(args, "include");
    try {
      var root = workspace.resolveSafe(pathArg);
      if (!Files.isDirectory(root)) {
        return ToolResult.failure("Grep: not a directory: " + workspace.relativize(root));
      }
      var includeMatcher =
          includeArg.isEmpty() ? null : GlobMatchers.compile(root.getFileSystem(), includeArg);
      var out = new StringBuilder();
      var matchCount = new int[] {0};
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              if (ctx.cancellation().isCancelled()) {
                return FileVisitResult.TERMINATE;
              }
              if (!dir.equals(root) && dir.getFileName().toString().startsWith(".")) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              if (ctx.cancellation().isCancelled() || matchCount[0] >= MAX_MATCHES) {
                return FileVisitResult.TERMINATE;
              }
              if (attrs.size() > MAX_FILE_BYTES) {
                return FileVisitResult.CONTINUE;
              }
              if (includeMatcher != null) {
                var name = file.getFileName();
                if (name == null || !includeMatcher.matches(name)) {
                  return FileVisitResult.CONTINUE;
                }
              }
              try {
                if (isBinary(file)) {
                  return FileVisitResult.CONTINUE;
                }
                var relPath = workspace.relativize(file);
                var lineNum = 0;
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                  String line;
                  while ((line = reader.readLine()) != null) {
                    lineNum++;
                    if (regex.matcher(line).find()) {
                      var emittedLine = redactor == null ? line : redactor.redact(line).text();
                      out.append(relPath)
                          .append(':')
                          .append(lineNum)
                          .append(':')
                          .append(emittedLine)
                          .append('\n');
                      matchCount[0]++;
                      if (matchCount[0] >= MAX_MATCHES) {
                        return FileVisitResult.TERMINATE;
                      }
                    }
                  }
                } catch (MalformedInputException e) {
                  // Not valid UTF-8 — treat as binary and skip.
                }
              } catch (IOException e) {
                // Unreadable file — skip silently rather than failing the entire search.
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
              return FileVisitResult.CONTINUE;
            }
          });
      if (matchCount[0] >= MAX_MATCHES) {
        out.append("[truncated at ").append(MAX_MATCHES).append(" matches]\n");
      } else if (matchCount[0] == 0) {
        return ToolResult.success("");
      }
      return ToolResult.success(out.toString());
    } catch (WorkspaceRoot.WorkspaceEscapeException e) {
      return ToolResult.failure("Grep: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      return ToolResult.failure(
          "Grep: invalid include pattern '" + includeArg + "': " + e.getMessage());
    } catch (IOException e) {
      return ToolResult.failure("Grep: I/O error scanning " + pathArg + ": " + e.getMessage());
    }
  }

  private static boolean isBinary(Path file) throws IOException {
    try (var in = Files.newInputStream(file)) {
      var buf = new byte[BINARY_SNIFF_BYTES];
      var n = in.read(buf);
      for (var i = 0; i < n; i++) {
        if (buf[i] == 0) {
          return true;
        }
      }
      return false;
    }
  }
}
