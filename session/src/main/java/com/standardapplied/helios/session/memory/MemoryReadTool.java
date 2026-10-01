/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.memory;

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
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Built-in {@code MemoryRead} tool. Reads a single memory file or lists the entries under a prefix
 * via a {@link MemoryBackend}.
 *
 * <p>Arguments:
 *
 * <ul>
 *   <li>{@code path} (required) — must start with {@code /memories/}. If the path resolves to a
 *       file, the tool returns its content; if a {@code list} flag is true or the path resolves to
 *       a directory-style prefix, the tool returns a newline-separated index of entries.
 *   <li>{@code list} (optional, boolean) — force the listing branch even if the path resolves to a
 *       regular file. Useful for advertising the memory tree from {@code /memories/}.
 * </ul>
 *
 * <p>{@link ToolCategory#READ}; permission key carries the requested path so the permission system
 * can match {@code /memories/**} globs.
 */
public final class MemoryReadTool {

  /** The stable tool name advertised to the model. */
  public static final String NAME = "MemoryRead";

  private MemoryReadTool() {}

  /**
   * Build a binding bound to the given backend.
   *
   * @param backend the storage adapter; non-null
   * @return a fresh binding
   * @throws NullPointerException if {@code backend} is null
   */
  public static ToolBinding binding(MemoryBackend backend) {
    Objects.requireNonNull(backend, "backend must not be null");
    var tool =
        Tool.newBuilder()
            .withName(NAME)
            .withDescription(
                "Reads a memory file under /memories/, or lists the entries under a prefix when "
                    + "'list' is true.")
            .withParameters(
                List.of(
                    ToolParameter.newBuilder()
                        .withName("path")
                        .withType(ParameterType.STRING)
                        .withDescription("The /memories/... path to view or list. Required.")
                        .withRequired(true)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("list")
                        .withType(ParameterType.BOOLEAN)
                        .withDescription(
                            "Return an index of entries under 'path' rather than file content.")
                        .withRequired(false)
                        .build()))
            .withIdempotent(true)
            .withExecutor((args, ctx) -> execute(ctx, backend, args))
            .build();
    return ToolBinding.newBuilder(tool)
        .withCategory(ToolCategory.READ)
        .withPermissionKeyExtractor(
            args -> new ToolPermissionKey(NAME, ToolArgs.stringArg(args, "path")))
        .build();
  }

  private static ToolResult execute(
      ToolContext ctx, MemoryBackend backend, Map<String, Object> args) {
    ctx.cancellation().throwIfCancelled();
    var path = ToolArgs.stringArg(args, "path");
    if (path.isEmpty()) {
      return ToolResult.failure("MemoryRead: missing required 'path' argument");
    }
    var list = ToolArgs.boolArg(args, "list", false);
    try {
      if (list) {
        var entries = backend.list(path);
        if (entries.isEmpty()) {
          return ToolResult.success("(empty)\n");
        }
        var sb = new StringBuilder();
        for (var e : entries) {
          sb.append(e).append('\n');
        }
        return ToolResult.success(sb.toString());
      }
      return ToolResult.success(backend.view(path));
    } catch (NoSuchFileException e) {
      return ToolResult.failure("MemoryRead: no such memory entry: " + path);
    } catch (IllegalArgumentException e) {
      return ToolResult.failure("MemoryRead: " + e.getMessage());
    } catch (IOException e) {
      return ToolResult.failure("MemoryRead: I/O error reading " + path + ": " + e.getMessage());
    }
  }
}
