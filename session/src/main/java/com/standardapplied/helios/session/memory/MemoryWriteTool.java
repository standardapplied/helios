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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Built-in {@code MemoryWrite} tool. Single tool routing {@code create} / {@code str_replace} /
 * {@code insert} / {@code delete} operations on the agent's {@link MemoryBackend}. Splitting the
 * surface across two tools (read vs write) lets the permission system categorise them differently —
 * {@code MemoryRead} is {@link ToolCategory#READ} (default-allow), {@code MemoryWrite} is {@link
 * ToolCategory#WRITE} (default-ask).
 *
 * <p>Arguments:
 *
 * <ul>
 *   <li>{@code op} (required) — one of {@code "create"}, {@code "str_replace"}, {@code "insert"},
 *       {@code "delete"}.
 *   <li>{@code path} (required) — the {@code /memories/...} target.
 *   <li>{@code content} (for {@code create} and {@code insert}) — UTF-8 content to write or insert.
 *   <li>{@code oldString} and {@code newString} (for {@code str_replace}).
 *   <li>{@code lineNumber} (for {@code insert}) — 1-based line position.
 * </ul>
 *
 * <p>Failures from the backend surface as {@link ToolResult#failure(String)} so the model can
 * self-correct (e.g. by re-reading the file when an {@code oldString} match collision happens).
 */
public final class MemoryWriteTool {

  /** The stable tool name advertised to the model. */
  public static final String NAME = "MemoryWrite";

  private MemoryWriteTool() {}

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
                "Writes a memory file under /memories/. Operations: 'create' (refuses to "
                    + "overwrite), 'str_replace' (single-occurrence replace), 'insert' (1-based "
                    + "line position), 'delete'.")
            .withParameters(
                List.of(
                    ToolParameter.newBuilder()
                        .withName("op")
                        .withType(ParameterType.STRING)
                        .withDescription("Required. One of: create, str_replace, insert, delete.")
                        .withRequired(true)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("path")
                        .withType(ParameterType.STRING)
                        .withDescription("Required. /memories/... target path.")
                        .withRequired(true)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("content")
                        .withType(ParameterType.STRING)
                        .withDescription("Content for create / insert.")
                        .withRequired(false)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("oldString")
                        .withType(ParameterType.STRING)
                        .withDescription("Existing string to find for str_replace.")
                        .withRequired(false)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("newString")
                        .withType(ParameterType.STRING)
                        .withDescription("Replacement string for str_replace.")
                        .withRequired(false)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("lineNumber")
                        .withType(ParameterType.INTEGER)
                        .withDescription("1-based line position for insert.")
                        .withRequired(false)
                        .build()))
            .withIdempotent(false)
            .withExecutor((args, ctx) -> execute(ctx, backend, args))
            .build();
    return ToolBinding.newBuilder(tool)
        .withCategory(ToolCategory.WRITE)
        .withPermissionKeyExtractor(
            args -> new ToolPermissionKey(NAME, ToolArgs.stringArg(args, "path")))
        .build();
  }

  private static ToolResult execute(
      ToolContext ctx, MemoryBackend backend, Map<String, Object> args) {
    ctx.cancellation().throwIfCancelled();
    var op = ToolArgs.stringArg(args, "op");
    if (op.isEmpty()) {
      return ToolResult.failure("MemoryWrite: missing required 'op' argument");
    }
    var path = ToolArgs.stringArg(args, "path");
    if (path.isEmpty()) {
      return ToolResult.failure("MemoryWrite: missing required 'path' argument");
    }
    try {
      return switch (op) {
        case "create" -> {
          var content = ToolArgs.stringArgOrNull(args, "content");
          if (content == null) {
            yield ToolResult.failure("MemoryWrite: 'create' requires 'content'");
          }
          backend.create(path, content);
          yield ToolResult.success("created " + path + " (" + content.length() + " chars)");
        }
        case "str_replace" -> {
          var oldString = ToolArgs.stringArgOrNull(args, "oldString");
          var newString = ToolArgs.stringArgOrNull(args, "newString");
          if (oldString == null || newString == null) {
            yield ToolResult.failure(
                "MemoryWrite: 'str_replace' requires both 'oldString' and 'newString'");
          }
          backend.strReplace(path, oldString, newString);
          yield ToolResult.success(
              "replaced 1 occurrence in "
                  + path
                  + " ("
                  + oldString.length()
                  + " → "
                  + newString.length()
                  + " chars)");
        }
        case "insert" -> {
          var content = ToolArgs.stringArgOrNull(args, "content");
          var lineNumber = ToolArgs.intArg(args, "lineNumber", Integer.MIN_VALUE);
          if (content == null) {
            yield ToolResult.failure("MemoryWrite: 'insert' requires 'content'");
          }
          if (lineNumber == Integer.MIN_VALUE) {
            yield ToolResult.failure("MemoryWrite: 'insert' requires 'lineNumber'");
          }
          backend.insert(path, lineNumber, content);
          yield ToolResult.success(
              "inserted at line "
                  + lineNumber
                  + " in "
                  + path
                  + " ("
                  + content.length()
                  + " chars)");
        }
        case "delete" -> {
          backend.delete(path);
          yield ToolResult.success("deleted " + path);
        }
        default ->
            ToolResult.failure(
                "MemoryWrite: unknown op '"
                    + op
                    + "' (expected: create, str_replace, insert, delete)");
      };
    } catch (FileAlreadyExistsException e) {
      return ToolResult.failure("MemoryWrite: entry already exists at " + path);
    } catch (NoSuchFileException e) {
      return ToolResult.failure("MemoryWrite: no such memory entry: " + path);
    } catch (IllegalArgumentException e) {
      return ToolResult.failure("MemoryWrite: " + e.getMessage());
    } catch (IOException e) {
      return ToolResult.failure("MemoryWrite: I/O error: " + e.getMessage());
    }
  }
}
