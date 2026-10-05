/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

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
import java.io.IOException;
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
    return switch (GlobRequest.parse(args)) {
      case Result.Failure<GlobRequest> failure -> ToolResult.failure(failure.error());
      case Result.Success<GlobRequest> request -> glob(ctx, workspace, request.value());
    };
  }

  private static ToolResult glob(ToolContext ctx, WorkspaceRoot workspace, GlobRequest request) {
    try {
      var root = workspace.resolveSafe(request.path());
      if (!workspace.attributes(root).isDirectory()) {
        return ToolResult.failure("Glob: not a directory: " + workspace.relativize(root));
      }
      var matcher = GlobMatchers.compile(root.getFileSystem(), request.pattern());
      var hits = new ArrayList<Match>();
      var count =
          WorkspaceWalk.run(
              workspace,
              root,
              ctx.cancellation(),
              MAX_RESULTS,
              Long.MAX_VALUE,
              (file, attrs, remaining) -> {
                if (!matcher.matches(root.relativize(file))) {
                  return 0;
                }
                hits.add(
                    new Match(workspace.relativize(file), attrs.lastModifiedTime().toMillis()));
                return 1;
              });
      return ToolResult.success(format(hits, count >= MAX_RESULTS));
    } catch (WorkspaceRoot.WorkspaceEscapeException e) {
      return ToolResult.failure("Glob: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      return ToolResult.failure(
          "Glob: invalid pattern '" + request.pattern() + "': " + e.getMessage());
    } catch (IOException e) {
      return ToolResult.failure(
          "Glob: I/O error scanning " + request.path() + ": " + e.getMessage());
    }
  }

  private static String format(List<Match> hits, boolean truncated) {
    hits.sort(Comparator.<Match>comparingLong(m -> m.mtime).reversed());
    var out = new StringBuilder();
    for (var hit : hits) {
      out.append(hit.path).append('\n');
    }
    if (truncated) {
      out.append("[truncated at ").append(MAX_RESULTS).append(" results]\n");
    }
    return out.toString();
  }

  private record GlobRequest(String pattern, String path) {

    static Result<GlobRequest> parse(Map<String, Object> args) {
      var pattern = ToolArgs.stringArg(args, "pattern");
      if (Strings.isBlank(pattern)) {
        return new Result.Failure<>("Glob: missing required 'pattern' argument");
      }
      return new Result.Success<>(new GlobRequest(pattern, ToolArgs.pathArg(args)));
    }
  }

  private record Match(String path, long mtime) {}
}
