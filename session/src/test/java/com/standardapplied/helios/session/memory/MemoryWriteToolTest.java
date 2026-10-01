/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.session.files.WorkspaceRoot;
import ai.singlr.session.tools.ToolCategory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
            .execute(Map.of("op", "create", "path", "/memories/n.md", "content", "hello"));
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
            .execute(Map.of("op", "create", "path", "/memories/n.md", "content", "new"));
    assertFalse(result.success());
    assertTrue(result.output().contains("already exists"), result.output());
  }

  @Test
  void createRequiresContent(@TempDir Path tmp) {
    var result =
        MemoryWriteTool.binding(backend(tmp))
            .tool()
            .execute(Map.of("op", "create", "path", "/memories/n.md"));
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
                    "newString", "slow"));
    assertTrue(result.success(), result.output());
    assertEquals("the slow brown fox", backend.view("/memories/n.md"));
  }

  @Test
  void strReplaceRequiresBothStrings(@TempDir Path tmp) throws IOException {
    var backend = seeded(tmp, "n.md", "x");
    var result =
        MemoryWriteTool.binding(backend)
            .tool()
            .execute(Map.of("op", "str_replace", "path", "/memories/n.md", "oldString", "x"));
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
                    "newString", "Z"));
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
                    "content", "two"));
    assertTrue(result.success(), result.output());
    assertEquals("one\ntwo\nthree\n", backend.view("/memories/n.md"));
  }

  @Test
  void insertRequiresContent(@TempDir Path tmp) throws IOException {
    var backend = seeded(tmp, "n.md", "x\n");
    var result =
        MemoryWriteTool.binding(backend)
            .tool()
            .execute(Map.of("op", "insert", "path", "/memories/n.md", "lineNumber", 1));
    assertFalse(result.success());
    assertTrue(result.output().contains("requires 'content'"), result.output());
  }

  @Test
  void insertRequiresLineNumber(@TempDir Path tmp) throws IOException {
    var backend = seeded(tmp, "n.md", "x\n");
    var result =
        MemoryWriteTool.binding(backend)
            .tool()
            .execute(Map.of("op", "insert", "path", "/memories/n.md", "content", "y"));
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
                    "content", "y"));
    assertTrue(result.success(), result.output());
  }

  // ── delete ────────────────────────────────────────────────────────────────

  @Test
  void deleteWorks(@TempDir Path tmp) throws IOException {
    var backend = seeded(tmp, "n.md", "bye");
    var result =
        MemoryWriteTool.binding(backend)
            .tool()
            .execute(Map.of("op", "delete", "path", "/memories/n.md"));
    assertTrue(result.success(), result.output());
    assertFalse(Files.exists(tmp.resolve(FileSystemMemoryBackend.STORAGE_SUBDIR + "/n.md")));
  }

  @Test
  void deleteMissingFails(@TempDir Path tmp) {
    var result =
        MemoryWriteTool.binding(backend(tmp))
            .tool()
            .execute(Map.of("op", "delete", "path", "/memories/nope.md"));
    assertFalse(result.success());
    assertTrue(result.output().contains("no such memory entry"), result.output());
  }

  // ── parameter / dispatch errors ───────────────────────────────────────────

  @Test
  void missingOpFails(@TempDir Path tmp) {
    var result =
        MemoryWriteTool.binding(backend(tmp))
            .tool()
            .execute(Map.of("path", "/memories/n.md", "content", "x"));
    assertFalse(result.success());
    assertTrue(result.output().contains("missing required 'op'"), result.output());
  }

  @Test
  void missingPathFails(@TempDir Path tmp) {
    var result =
        MemoryWriteTool.binding(backend(tmp))
            .tool()
            .execute(Map.of("op", "create", "content", "x"));
    assertFalse(result.success());
    assertTrue(result.output().contains("missing required 'path'"), result.output());
  }

  @Test
  void unknownOpFails(@TempDir Path tmp) {
    var result =
        MemoryWriteTool.binding(backend(tmp))
            .tool()
            .execute(Map.of("op", "destroy", "path", "/memories/n.md"));
    assertFalse(result.success());
    assertTrue(result.output().contains("unknown op 'destroy'"), result.output());
  }

  @Test
  void badPathSurfacesFailure(@TempDir Path tmp) {
    var result =
        MemoryWriteTool.binding(backend(tmp))
            .tool()
            .execute(Map.of("op", "create", "path", "/etc/passwd", "content", "h@x"));
    assertFalse(result.success());
    assertTrue(result.output().contains("MemoryWrite:"), result.output());
  }

  @Test
  void rejectsNullBackend() {
    assertThrows(NullPointerException.class, () -> MemoryWriteTool.binding(null));
  }
}
