/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.session.tools.ToolCategory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class LsToolTest {

  @Test
  void listsDirectoryContents(@TempDir Path tmp) throws IOException {
    Files.createDirectory(tmp.resolve("alpha"));
    Files.writeString(tmp.resolve("beta.txt"), "x", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("aaa.md"), "y", StandardCharsets.UTF_8);
    Files.createDirectory(tmp.resolve("zeta"));

    var result =
        LsTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "."), ToolContext.noop());

    assertTrue(result.success(), result.output());
    var out = result.output();
    assertTrue(out.contains("alpha/"), out);
    assertTrue(out.contains("zeta/"), out);
    assertTrue(out.contains("aaa.md"), out);
    assertTrue(out.contains("beta.txt"), out);
    assertTrue(out.indexOf("alpha/") < out.indexOf("zeta/"), out);
    assertTrue(out.indexOf("zeta/") < out.indexOf("aaa.md"), out);
    assertTrue(out.indexOf("aaa.md") < out.indexOf("beta.txt"), out);
  }

  @Test
  void categoryIsRead(@TempDir Path tmp) {
    var binding = LsTool.binding(WorkspaceRoot.of(tmp));
    assertEquals(ToolCategory.READ, binding.category());
    assertEquals("LS", binding.name());
    assertEquals("LS", LsTool.NAME);
  }

  @Test
  void defaultPathListsRoot(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("readme"), "", StandardCharsets.UTF_8);
    var result = LsTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of(), ToolContext.noop());
    assertTrue(result.success(), result.output());
    assertTrue(result.output().contains("readme"));
  }

  @Test
  void emptyPathFallsBackToDefault(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("readme"), "", StandardCharsets.UTF_8);
    var result =
        LsTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", ""), ToolContext.noop());
    assertTrue(result.success());
    assertTrue(result.output().contains("readme"));
  }

  @Test
  void nonStringPathFallsBackToDefault(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("readme"), "", StandardCharsets.UTF_8);
    var result =
        LsTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", 42), ToolContext.noop());
    assertTrue(result.success());
    assertTrue(result.output().contains("readme"));
  }

  @Test
  void notADirectoryFails(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("file.txt"), "x", StandardCharsets.UTF_8);
    var result =
        LsTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "file.txt"), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("not a directory"), result.output());
  }

  @Test
  void escapingWorkspaceFails(@TempDir Path tmp) {
    var result =
        LsTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "../etc"), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().startsWith("LS:"), result.output());
  }

  @Test
  void permissionKeyCarriesPath(@TempDir Path tmp) throws IOException {
    Files.createDirectory(tmp.resolve("src"));
    var binding = LsTool.binding(WorkspaceRoot.of(tmp));
    assertEquals("src", binding.permissionKey(Map.of("path", "src")).canonicalArgs());
    assertEquals(".", binding.permissionKey(Map.of()).canonicalArgs());
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void symlinkRendersWithAt(@TempDir Path tmp) throws IOException {
    var target = tmp.resolve("target.txt");
    Files.writeString(target, "x", StandardCharsets.UTF_8);
    Files.createSymbolicLink(tmp.resolve("link.txt"), target);
    var result =
        LsTool.binding(new WorkspaceRoot(tmp, false))
            .tool()
            .execute(Map.of("path", "."), ToolContext.noop());
    assertTrue(result.success(), result.output());
    assertTrue(result.output().contains("link.txt@"), result.output());
    assertTrue(result.output().contains("target.txt"), result.output());
  }

  @Test
  void rejectsNullWorkspace() {
    assertThrows(NullPointerException.class, () -> LsTool.binding(null));
  }
}
