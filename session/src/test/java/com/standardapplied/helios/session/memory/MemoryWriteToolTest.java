/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.tools.ToolCategory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class MemoryWriteToolTest {

  private static FileSystemMemoryBackend backend(Path tmp) {
    return FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
  }

  private static FileSystemMemoryBackend seeded(Path tmp, String rel, String content)
      throws IOException {
    var target = tmp.resolve(FileSystemMemoryBackend.STORAGE_SUBDIR).resolve(rel);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content, StandardCharsets.UTF_8);
    return backend(tmp);
  }

  @Test
  void categoryIsWriteAndPermissionKeyCarriesPath(@TempDir Path tmp) {
    var binding = MemoryWriteTool.binding(backend(tmp));
    assertEquals(ToolCategory.WRITE, binding.category());
    assertEquals("MemoryWrite", binding.name());
    assertEquals("MemoryWrite", MemoryWriteTool.NAME);
    assertEquals(
        "/memories/x.md", binding.permissionKey(Map.of("path", "/memories/x.md")).canonicalArgs());
  }

  // ── create ────────────────────────────────────────────────────────────────

  @Test
  void createWritesNewFile(@TempDir Path tmp) throws IOException {
    var backend = backend(tmp);
    var result =
        MemoryWriteTool.binding(backend)
            .tool()
            .execute(
                Map.of("op", "create", "path", "/memories/n.md", "content", "hello"),
                ToolContext.noop());
    assertTrue(result.success(), result.output());
    assertEquals("hello", backend.view("/memories/n.md"));
    assertTrue(result.output().contains("created /memories/n.md"));
  }

  @Test
  void createRefusesExisting(@TempDir Path tmp) throws IOException {
    var backend = seeded(tmp, "n.md", "old");
    var result =
        MemoryWriteTool.binding(backend)
            .tool()
            .execute(
                Map.of("op", "create", "path", "/memories/n.md", "content", "new"),
                ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("already exists"), result.output());
  }

  @Test
  void createRequiresContent(@TempDir Path tmp) {
    var result =
        MemoryWriteTool.binding(backend(tmp))
            .tool()
            .execute(Map.of("op", "create", "path", "/memories/n.md"), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("requires 'content'"), result.output());
  }

  // ── str_replace ───────────────────────────────────────────────────────────

  @Test
  void strReplaceWorks(@TempDir Path tmp) throws IOException {
    var backend = seeded(tmp, "n.md", "the quick brown fox");
    var result =
        MemoryWriteTool.binding(backend)
            .tool()
            .execute(
                Map.of(
                    "op", "str_replace",
                    "path", "/memories/n.md",
                    "oldString", "quick",
                    "newString", "slow"),
                ToolContext.noop());
    assertTrue(result.success(), result.output());
    assertEquals("the slow brown fox", backend.view("/memories/n.md"));
  }

  @Test
  void strReplaceRequiresBothStrings(@TempDir Path tmp) throws IOException {
    var backend = seeded(tmp, "n.md", "x");
    var result =
        MemoryWriteTool.binding(backend)
            .tool()
            .execute(
                Map.of("op", "str_replace", "path", "/memories/n.md", "oldString", "x"),
                ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("requires both"), result.output());
  }

  @Test
  void strReplaceSurfacesAmbiguousMatchFailure(@TempDir Path tmp) throws IOException {
    var backend = seeded(tmp, "n.md", "a a");
    var result =
        MemoryWriteTool.binding(backend)
            .tool()
            .execute(
                Map.of(
                    "op", "str_replace",
                    "path", "/memories/n.md",
                    "oldString", "a",
                    "newString", "Z"),
                ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("more than once"), result.output());
  }

  // ── insert ────────────────────────────────────────────────────────────────

  @Test
  void insertWorks(@TempDir Path tmp) throws IOException {
    var backend = seeded(tmp, "n.md", "one\nthree\n");
    var result =
        MemoryWriteTool.binding(backend)
            .tool()
            .execute(
                Map.of(
                    "op", "insert",
                    "path", "/memories/n.md",
                    "lineNumber", 2,
                    "content", "two"),
                ToolContext.noop());
    assertTrue(result.success(), result.output());
    assertEquals("one\ntwo\nthree\n", backend.view("/memories/n.md"));
  }

  @Test
  void insertRequiresContent(@TempDir Path tmp) throws IOException {
    var backend = seeded(tmp, "n.md", "x\n");
    var result =
        MemoryWriteTool.binding(backend)
            .tool()
            .execute(
                Map.of("op", "insert", "path", "/memories/n.md", "lineNumber", 1),
                ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("requires 'content'"), result.output());
  }

  @Test
  void insertRequiresLineNumber(@TempDir Path tmp) throws IOException {
    var backend = seeded(tmp, "n.md", "x\n");
    var result =
        MemoryWriteTool.binding(backend)
            .tool()
            .execute(
                Map.of("op", "insert", "path", "/memories/n.md", "content", "y"),
                ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("requires 'lineNumber'"), result.output());
  }

  @Test
  void insertAcceptsLongLineNumber(@TempDir Path tmp) throws IOException {
    var backend = seeded(tmp, "n.md", "x\n");
    var result =
        MemoryWriteTool.binding(backend)
            .tool()
            .execute(
                Map.of(
                    "op", "insert",
                    "path", "/memories/n.md",
                    "lineNumber", 1L,
                    "content", "y"),
                ToolContext.noop());
    assertTrue(result.success(), result.output());
  }

  // ── delete ────────────────────────────────────────────────────────────────

  @Test
  void deleteWorks(@TempDir Path tmp) throws IOException {
    var backend = seeded(tmp, "n.md", "bye");
    var result =
        MemoryWriteTool.binding(backend)
            .tool()
            .execute(Map.of("op", "delete", "path", "/memories/n.md"), ToolContext.noop());
    assertTrue(result.success(), result.output());
    assertFalse(Files.exists(tmp.resolve(FileSystemMemoryBackend.STORAGE_SUBDIR + "/n.md")));
  }

  @Test
  void deleteMissingFails(@TempDir Path tmp) {
    var result =
        MemoryWriteTool.binding(backend(tmp))
            .tool()
            .execute(Map.of("op", "delete", "path", "/memories/nope.md"), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("no such memory entry"), result.output());
  }

  // ── parameter / dispatch errors ───────────────────────────────────────────

  @Test
  void missingOpFails(@TempDir Path tmp) {
    var result =
        MemoryWriteTool.binding(backend(tmp))
            .tool()
            .execute(Map.of("path", "/memories/n.md", "content", "x"), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("missing required 'op'"), result.output());
  }

  @Test
  void missingPathFails(@TempDir Path tmp) {
    var result =
        MemoryWriteTool.binding(backend(tmp))
            .tool()
            .execute(Map.of("op", "create", "content", "x"), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("missing required 'path'"), result.output());
  }

  @Test
  void unknownOpFails(@TempDir Path tmp) {
    var result =
        MemoryWriteTool.binding(backend(tmp))
            .tool()
            .execute(Map.of("op", "destroy", "path", "/memories/n.md"), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("unknown op 'destroy'"), result.output());
  }

  @Test
  void badPathSurfacesFailure(@TempDir Path tmp) {
    var result =
        MemoryWriteTool.binding(backend(tmp))
            .tool()
            .execute(
                Map.of("op", "create", "path", "/etc/passwd", "content", "h@x"),
                ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("MemoryWrite:"), result.output());
  }

  @Test
  void rejectsNullBackend() {
    assertThrows(NullPointerException.class, () -> MemoryWriteTool.binding(null));
  }

  static Stream<Arguments> operationsWithExactOutcome() {
    return Stream.of(
        Arguments.of(
            Map.of("path", "/memories/a.md"), false, "MemoryWrite: missing required 'op' argument"),
        Arguments.of(
            Map.of("op", "create"), false, "MemoryWrite: missing required 'path' argument"),
        Arguments.of(
            Map.of("op", "create", "path", "/memories/a.md"),
            false,
            "MemoryWrite: 'create' requires 'content'"),
        Arguments.of(
            Map.of("op", "create", "path", "/memories/n.md", "content", "abc"),
            true,
            "created /memories/n.md (3 chars)"),
        Arguments.of(
            Map.of("op", "create", "path", "/memories/a.md", "content", "abc"),
            false,
            "MemoryWrite: entry already exists at /memories/a.md"),
        Arguments.of(
            Map.of("op", "str_replace", "path", "/memories/a.md", "oldString", "one"),
            false,
            "MemoryWrite: 'str_replace' requires both 'oldString' and 'newString'"),
        Arguments.of(
            Map.of("op", "str_replace", "path", "/memories/a.md", "newString", "one"),
            false,
            "MemoryWrite: 'str_replace' requires both 'oldString' and 'newString'"),
        Arguments.of(
            Map.of(
                "op",
                "str_replace",
                "path",
                "/memories/a.md",
                "oldString",
                "one",
                "newString",
                "three"),
            true,
            "replaced 1 occurrence in /memories/a.md (3 → 5 chars)"),
        Arguments.of(
            Map.of("op", "insert", "path", "/memories/a.md", "lineNumber", 1),
            false,
            "MemoryWrite: 'insert' requires 'content'"),
        Arguments.of(
            Map.of("op", "insert", "path", "/memories/a.md", "content", "x"),
            false,
            "MemoryWrite: 'insert' requires 'lineNumber'"),
        Arguments.of(
            Map.of("op", "insert", "path", "/memories/a.md", "content", "zero", "lineNumber", 1),
            true,
            "inserted at line 1 in /memories/a.md (4 chars)"),
        Arguments.of(
            Map.of("op", "insert", "path", "/memories/a.md", "content", "x", "lineNumber", 9),
            false,
            "MemoryWrite: lineNumber 9 out of range [1, 3]"),
        Arguments.of(
            Map.of("op", "delete", "path", "/memories/missing.md"),
            false,
            "MemoryWrite: no such memory entry: /memories/missing.md"),
        Arguments.of(
            Map.of("op", "delete", "path", "/memories/a.md"), true, "deleted /memories/a.md"),
        Arguments.of(
            Map.of("op", "delete", "path", "/memories/dir"),
            false,
            "MemoryWrite: I/O error: delete: refusing to delete a directory: /memories/dir"),
        Arguments.of(
            Map.of("op", "view", "path", "/memories/a.md"),
            false,
            "MemoryWrite: unknown op 'view' (expected: create, str_replace, insert, delete)"));
  }

  @ParameterizedTest
  @MethodSource("operationsWithExactOutcome")
  void operationReportsExactOutcome(
      Map<String, Object> args, boolean success, String output, @TempDir Path tmp)
      throws IOException {
    var backend = seeded(tmp, "a.md", "one\ntwo\n");
    Files.createDirectories(tmp.resolve(FileSystemMemoryBackend.STORAGE_SUBDIR).resolve("dir"));
    var result = MemoryWriteTool.binding(backend).tool().execute(args, ToolContext.noop());
    assertEquals(success, result.success(), result.output());
    assertEquals(output, result.output());
  }
}
