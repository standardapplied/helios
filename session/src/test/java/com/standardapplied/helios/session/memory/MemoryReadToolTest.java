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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MemoryReadToolTest {

  private static FileSystemMemoryBackend seeded(Path tmp, String relPath, String content)
      throws IOException {
    var target = tmp.resolve(FileSystemMemoryBackend.STORAGE_SUBDIR).resolve(relPath);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content, StandardCharsets.UTF_8);
    return FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
  }

  @Test
  void readsFileContent(@TempDir Path tmp) throws IOException {
    var backend = seeded(tmp, "notes.md", "memory contents");
    var result =
        MemoryReadTool.binding(backend).tool().execute(Map.of("path", "/memories/notes.md"));
    assertTrue(result.success(), result.output());
    assertEquals("memory contents", result.output());
  }

  @Test
  void categoryIsReadAndPermissionKeyCarriesPath(@TempDir Path tmp) throws IOException {
    var backend = seeded(tmp, "x.md", "x");
    var binding = MemoryReadTool.binding(backend);
    assertEquals(ToolCategory.READ, binding.category());
    assertEquals("MemoryRead", binding.name());
    assertEquals("MemoryRead", MemoryReadTool.NAME);
    assertEquals(
        "/memories/x.md", binding.permissionKey(Map.of("path", "/memories/x.md")).canonicalArgs());
  }

  @Test
  void listFlagReturnsIndex(@TempDir Path tmp) throws IOException {
    seeded(tmp, "INDEX.md", "");
    seeded(tmp, "user/a.md", "");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var result =
        MemoryReadTool.binding(backend).tool().execute(Map.of("path", "/memories/", "list", true));
    assertTrue(result.success(), result.output());
    var lines = List.of(result.output().split("\n"));
    assertTrue(lines.contains("/memories/INDEX.md"));
    assertTrue(lines.contains("/memories/user/a.md"));
  }

  @Test
  void emptyListReturnsExplicitMarker(@TempDir Path tmp) throws IOException {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var result =
        MemoryReadTool.binding(backend).tool().execute(Map.of("path", "/memories/", "list", true));
    assertTrue(result.success(), result.output());
    assertEquals("(empty)\n", result.output());
  }

  @Test
  void missingPathFails(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var result = MemoryReadTool.binding(backend).tool().execute(Map.of());
    assertFalse(result.success());
    assertTrue(result.output().contains("missing required 'path'"), result.output());
  }

  @Test
  void missingFileFails(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var result =
        MemoryReadTool.binding(backend).tool().execute(Map.of("path", "/memories/nope.md"));
    assertFalse(result.success());
    assertTrue(result.output().contains("no such memory entry"), result.output());
  }

  @Test
  void badPathSurfacesAsFailure(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var result = MemoryReadTool.binding(backend).tool().execute(Map.of("path", "/etc/passwd"));
    assertFalse(result.success());
    assertTrue(result.output().contains("MemoryRead:"), result.output());
  }

  @Test
  void rejectsNullBackend() {
    assertThrows(NullPointerException.class, () -> MemoryReadTool.binding(null));
  }
}
