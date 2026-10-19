/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.memory;

import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.tools.ToolArgs;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.Withholding;
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
 * <p>{@link ToolCategory#READ}. Memory is its own namespace: the permission key is the canonical
 * memory path ({@code /memories/} or {@code /memories/a/b}), so a call is authorised by {@code
 * MemoryRead} rules on that key, and a {@code Read} rule over the directory backing a filesystem
 * memory store does not apply to it. A listing withholds every path a {@code MemoryRead} DENY rule
 * matches and ends with {@code [withheld by permission policy: <count>]}.
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
        .withPathArgument(ToolBinding.PathArgument.required("path", MemoryPaths.INSTANCE))
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
        return ToolResult.success(listing(ctx, backend.list(path)));
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

  private static String listing(ToolContext ctx, List<String> entries) {
    var withholding = Withholding.of(ctx);
    var out = new StringBuilder();
    for (var entry : entries) {
      if (!withholding.withholds(entry)) {
        out.append(entry).append('\n');
      }
    }
    return (out.isEmpty() ? "(empty)\n" : out) + withholding.note();
  }
}
