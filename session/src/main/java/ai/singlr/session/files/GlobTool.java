/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Built-in {@code Glob} tool. Matches workspace files against a glob pattern using {@link
 * java.nio.file.FileSystem#getPathMatcher(String)} with the {@code "glob:"} syntax.
 *
 * <p>Arguments:
 *
 * <ul>
 *   <li>{@code pattern} (required) — a {@code glob:} pattern (e.g. {@code "**\/*.java"}).
 *   <li>{@code path} (optional, default {@code "."}) — root to scan, workspace-relative or
 *       absolute.
 * </ul>
 *
 * <p>Results are sorted by modification time (newest first) so the model sees fresh edits at the
 * top — matches the Claude Code Glob convention.
 *
 * <p>Hidden directories (names starting with {@code "."}) are pruned during traversal except for
 * the root itself. The total result count is capped at {@code 1000} to bound output size.
 */
public final class GlobTool {

  /** The stable tool name advertised to the model. */
  public static final String NAME = "Glob";

  private static final int MAX_RESULTS = 1000;

  private GlobTool() {}

  /**
   * Build a tool binding bound to the given workspace.
   *
   * @param workspace the path-jail workspace; non-null
   * @return a ready-to-register binding
   * @throws NullPointerException if {@code workspace} is null
   */
  public static ToolBinding binding(WorkspaceRoot workspace) {
    Objects.requireNonNull(workspace, "workspace must not be null");
    var tool =
        Tool.newBuilder()
            .withName(NAME)
            .withDescription(
                "Finds files matching a glob pattern. Results sorted newest-first by mtime, "
                    + "capped at "
                    + MAX_RESULTS
                    + ".")
            .withParameters(
                List.of(
                    ToolParameter.newBuilder()
                        .withName("pattern")
                        .withType(ParameterType.STRING)
                        .withDescription(
                            "Glob pattern, e.g. '**/*.java' or 'src/**/Foo*.txt'. "
                                + "Matched against workspace-relative paths.")
                        .withRequired(true)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("path")
                        .withType(ParameterType.STRING)
                        .withDescription(
                            "Optional root directory for the search (defaults to workspace root).")
                        .withRequired(false)
                        .build()))
            .withIdempotent(true)
            .withExecutor((args, ctx) -> execute(ctx, workspace, args))
            .build();
    return ToolBinding.newBuilder(tool)
        .withCategory(ToolCategory.SEARCH)
        .withPermissionKeyExtractor(args -> new ToolPermissionKey(NAME, ToolArgs.pathArg(args)))
        .build();
  }

  private static ToolResult execute(
      ToolContext ctx, WorkspaceRoot workspace, Map<String, Object> args) {
    var pattern = ToolArgs.stringArg(args, "pattern");
    if (Strings.isBlank(pattern)) {
      return ToolResult.failure("Glob: missing required 'pattern' argument");
    }
    var pathArg = ToolArgs.pathArg(args);
    try {
      var root = workspace.resolveSafe(pathArg);
      if (!Files.isDirectory(root)) {
        return ToolResult.failure("Glob: not a directory: " + workspace.relativize(root));
      }
      var matcher = GlobMatchers.compile(root.getFileSystem(), pattern);
      var hits = new ArrayList<Match>();
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
              if (ctx.cancellation().isCancelled()) {
                return FileVisitResult.TERMINATE;
              }
              var rel = root.relativize(file);
              if (matcher.matches(rel) && hits.size() < MAX_RESULTS) {
                hits.add(
                    new Match(workspace.relativize(file), attrs.lastModifiedTime().toMillis()));
              }
              return hits.size() >= MAX_RESULTS
                  ? FileVisitResult.TERMINATE
                  : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
              return FileVisitResult.CONTINUE;
            }
          });
      hits.sort(Comparator.<Match>comparingLong(m -> m.mtime).reversed());
      var out = new StringBuilder();
      for (var hit : hits) {
        out.append(hit.path).append('\n');
      }
      if (hits.size() >= MAX_RESULTS) {
        out.append("[truncated at ").append(MAX_RESULTS).append(" results]\n");
      }
      return ToolResult.success(out.toString());
    } catch (WorkspaceRoot.WorkspaceEscapeException e) {
      return ToolResult.failure("Glob: " + e.getMessage());
    } catch (java.util.regex.PatternSyntaxException e) {
      return ToolResult.failure("Glob: invalid pattern '" + pattern + "': " + e.getMessage());
    } catch (IllegalArgumentException e) {
      return ToolResult.failure("Glob: invalid pattern '" + pattern + "': " + e.getMessage());
    } catch (IOException e) {
      return ToolResult.failure("Glob: I/O error scanning " + pathArg + ": " + e.getMessage());
    }
  }

  private record Match(String path, long mtime) {}
}
