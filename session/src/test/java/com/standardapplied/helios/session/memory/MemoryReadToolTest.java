/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
        MemoryReadTool.binding(backend)
            .tool()
            .execute(Map.of("path", "/memories/notes.md"), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
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
        "/memories/x.md", keyOf(binding, Map.of("path", "/memories/x.md")).canonicalArgs());
  }

  @Test
  void listFlagReturnsIndex(@TempDir Path tmp) throws IOException {
    seeded(tmp, "INDEX.md", "");
    seeded(tmp, "user/a.md", "");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var result =
        MemoryReadTool.binding(backend)
            .tool()
            .execute(Map.of("path", "/memories/", "list", true), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    var lines = List.of(result.output().split("\n"));
    assertTrue(lines.contains("/memories/INDEX.md"));
    assertTrue(lines.contains("/memories/user/a.md"));
  }

  @Test
  void emptyListReturnsExplicitMarker(@TempDir Path tmp) throws IOException {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var result =
        MemoryReadTool.binding(backend)
            .tool()
            .execute(Map.of("path", "/memories/", "list", true), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    assertEquals("(empty)\n", result.output());
  }

  @Test
  void missingPathFails(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var result = MemoryReadTool.binding(backend).tool().execute(Map.of(), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().contains("missing required 'path'"), result.output());
  }

  @Test
  void missingFileFails(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var result =
        MemoryReadTool.binding(backend)
            .tool()
            .execute(Map.of("path", "/memories/nope.md"), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().contains("no such memory entry"), result.output());
  }

  @Test
  void badPathSurfacesAsFailure(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var result =
        MemoryReadTool.binding(backend)
            .tool()
            .execute(Map.of("path", "/etc/passwd"), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().contains("MemoryRead:"), result.output());
  }

  @Test
  void rejectsNullBackend() {
    assertThrows(NullPointerException.class, () -> MemoryReadTool.binding(null));
  }

  private static ToolPermissionKey keyOf(ToolBinding binding, Map<String, Object> args) {
    return binding.canonicalize(new ToolCall("call-1", binding.name(), args)).key();
  }

  @Test
  void aListingWithEveryEntryWithheldIsEmptyThenNoted(@TempDir Path tmp) throws IOException {
    var backend = seeded(tmp, "locked.md", "locked");
    var context =
        new ToolContext(
            SessionContext.forTesting("memory"),
            new CancellationToken(),
            Duration.ofMinutes(1),
            "/memories/locked.md"::equals);

    var result =
        MemoryReadTool.binding(backend)
            .tool()
            .execute(Map.of("path", "/memories/", "list", true), context);

    assertEquals("(empty)\n[withheld by permission policy: 1]\n", result.output());
  }
}
