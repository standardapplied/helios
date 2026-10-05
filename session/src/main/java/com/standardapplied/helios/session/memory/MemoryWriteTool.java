/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.memory;

import com.standardapplied.helios.core.common.Result;
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
    return switch (parse(args)) {
      case Result.Failure<Operation> failure -> ToolResult.failure(failure.error());
      case Result.Success<Operation> operation -> apply(backend, operation.value());
    };
  }

  private static Result<Operation> parse(Map<String, Object> args) {
    var op = ToolArgs.stringArg(args, "op");
    if (op.isEmpty()) {
      return new Result.Failure<>("MemoryWrite: missing required 'op' argument");
    }
    var path = ToolArgs.stringArg(args, "path");
    if (path.isEmpty()) {
      return new Result.Failure<>("MemoryWrite: missing required 'path' argument");
    }
    return switch (op) {
      case "create" -> Create.parse(path, args);
      case "str_replace" -> StrReplace.parse(path, args);
      case "insert" -> Insert.parse(path, args);
      case "delete" -> new Result.Success<>(new Delete(path));
      default ->
          new Result.Failure<>(
              "MemoryWrite: unknown op '"
                  + op
                  + "' (expected: create, str_replace, insert, delete)");
    };
  }

  private static ToolResult apply(MemoryBackend backend, Operation operation) {
    try {
      return ToolResult.success(operation.applyTo(backend));
    } catch (FileAlreadyExistsException e) {
      return ToolResult.failure("MemoryWrite: entry already exists at " + operation.path());
    } catch (NoSuchFileException e) {
      return ToolResult.failure("MemoryWrite: no such memory entry: " + operation.path());
    } catch (IllegalArgumentException e) {
      return ToolResult.failure("MemoryWrite: " + e.getMessage());
    } catch (IOException e) {
      return ToolResult.failure("MemoryWrite: I/O error: " + e.getMessage());
    }
  }

  private sealed interface Operation {

    String path();

    String applyTo(MemoryBackend backend) throws IOException;
  }

  private record Create(String path, String content) implements Operation {

    static Result<Operation> parse(String path, Map<String, Object> args) {
      var content = ToolArgs.stringArgOrNull(args, "content");
      if (content == null) {
        return new Result.Failure<>("MemoryWrite: 'create' requires 'content'");
      }
      return new Result.Success<>(new Create(path, content));
    }

    @Override
    public String applyTo(MemoryBackend backend) throws IOException {
      backend.create(path, content);
      return "created " + path + " (" + content.length() + " chars)";
    }
  }

  private record StrReplace(String path, String oldString, String newString) implements Operation {

    static Result<Operation> parse(String path, Map<String, Object> args) {
      var oldString = ToolArgs.stringArgOrNull(args, "oldString");
      var newString = ToolArgs.stringArgOrNull(args, "newString");
      if (oldString == null || newString == null) {
        return new Result.Failure<>(
            "MemoryWrite: 'str_replace' requires both 'oldString' and 'newString'");
      }
      return new Result.Success<>(new StrReplace(path, oldString, newString));
    }

    @Override
    public String applyTo(MemoryBackend backend) throws IOException {
      backend.strReplace(path, oldString, newString);
      return "replaced 1 occurrence in "
          + path
          + " ("
          + oldString.length()
          + " → "
          + newString.length()
          + " chars)";
    }
  }

  private record Insert(String path, int lineNumber, String content) implements Operation {

    static Result<Operation> parse(String path, Map<String, Object> args) {
      var content = ToolArgs.stringArgOrNull(args, "content");
      var lineNumber = ToolArgs.intArg(args, "lineNumber", Integer.MIN_VALUE);
      if (content == null) {
        return new Result.Failure<>("MemoryWrite: 'insert' requires 'content'");
      }
      if (lineNumber == Integer.MIN_VALUE) {
        return new Result.Failure<>("MemoryWrite: 'insert' requires 'lineNumber'");
      }
      return new Result.Success<>(new Insert(path, lineNumber, content));
    }

    @Override
    public String applyTo(MemoryBackend backend) throws IOException {
      backend.insert(path, lineNumber, content);
      return "inserted at line " + lineNumber + " in " + path + " (" + content.length() + " chars)";
    }
  }

  private record Delete(String path) implements Operation {

    @Override
    public String applyTo(MemoryBackend backend) throws IOException {
      backend.delete(path);
      return "deleted " + path;
    }
  }
}
