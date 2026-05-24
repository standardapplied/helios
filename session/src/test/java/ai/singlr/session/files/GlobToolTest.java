/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.session.tools.ToolCategory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GlobToolTest {

  @Test
  void recursivePatternMatchesRootFiles(@TempDir Path tmp) throws IOException {
    // Regression: raw JDK glob '**/*.md' requires at least one separator, so 'intro.md' at the
    // root doesn't match. Every other glob system agents have seen (rg, fd, gitignore) treats
    // '**/*.md' as recursive-including-root, and so do we via GlobMatchers.compile().
    Files.writeString(tmp.resolve("intro.md"), "# Intro\n", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("guide.md"), "# Guide\n", StandardCharsets.UTF_8);
    var nested = tmp.resolve("nested");
    Files.createDirectory(nested);
    Files.writeString(nested.resolve("deep.md"), "# Deep\n", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("config.yaml"), "key: value\n", StandardCharsets.UTF_8);

    var result =
        GlobTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of("pattern", "**/*.md"));

    assertTrue(result.success(), result.output());
    var out = result.output();
    assertTrue(out.contains("intro.md"), "root-level intro.md must match '**/*.md': " + out);
    assertTrue(out.contains("guide.md"), "root-level guide.md must match '**/*.md': " + out);
    assertTrue(out.contains("deep.md"), "nested deep.md must match '**/*.md': " + out);
    assertFalse(out.contains("config.yaml"), "yaml must not match '**/*.md': " + out);
  }

  @Test
  void nonRecursivePatternMatchesOnlyRoot(@TempDir Path tmp) throws IOException {
    // Boundary: a bare '*.md' must NOT match nested files. Pins the semantics so a future
    // "be more helpful" extension doesn't accidentally make every pattern recursive.
    Files.writeString(tmp.resolve("intro.md"), "# Intro\n", StandardCharsets.UTF_8);
    var nested = tmp.resolve("nested");
    Files.createDirectory(nested);
    Files.writeString(nested.resolve("deep.md"), "# Deep\n", StandardCharsets.UTF_8);

    var result = GlobTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of("pattern", "*.md"));

    assertTrue(result.success(), result.output());
    var out = result.output();
    assertTrue(out.contains("intro.md"), "root intro.md must match bare '*.md': " + out);
    assertFalse(out.contains("deep.md"), "nested deep.md must NOT match bare '*.md': " + out);
  }

  @Test
  void matchesGlobPattern(@TempDir Path tmp) throws IOException {
    Files.createDirectories(tmp.resolve("src/main"));
    Files.writeString(tmp.resolve("src/main/Foo.java"), "x", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("src/main/Bar.java"), "x", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("src/main/notes.md"), "x", StandardCharsets.UTF_8);

    var result =
        GlobTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of("pattern", "**/*.java"));

    assertTrue(result.success(), result.output());
    var out = result.output();
    assertTrue(out.contains("src/main/Foo.java"), out);
    assertTrue(out.contains("src/main/Bar.java"), out);
    assertFalse(out.contains("notes.md"));
  }

  @Test
  void categoryIsSearch(@TempDir Path tmp) {
    var binding = GlobTool.binding(WorkspaceRoot.of(tmp));
    assertEquals(ToolCategory.SEARCH, binding.category());
    assertEquals("Glob", binding.name());
    assertEquals("Glob", GlobTool.NAME);
  }

  @Test
  void sortsNewestFirstByMtime(@TempDir Path tmp) throws IOException {
    var older = tmp.resolve("older.txt");
    var newer = tmp.resolve("newer.txt");
    Files.writeString(older, "x", StandardCharsets.UTF_8);
    Files.writeString(newer, "x", StandardCharsets.UTF_8);
    Files.setLastModifiedTime(older, FileTime.from(Instant.now().minusSeconds(60)));
    Files.setLastModifiedTime(newer, FileTime.from(Instant.now()));

    var result = GlobTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of("pattern", "*.txt"));

    assertTrue(result.success(), result.output());
    var out = result.output();
    assertTrue(out.indexOf("newer.txt") < out.indexOf("older.txt"), out);
  }

  @Test
  void honorsExplicitPathRoot(@TempDir Path tmp) throws IOException {
    Files.createDirectory(tmp.resolve("a"));
    Files.createDirectory(tmp.resolve("b"));
    Files.writeString(tmp.resolve("a/match.java"), "x", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("b/match.java"), "x", StandardCharsets.UTF_8);

    var result =
        GlobTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "*.java", "path", "a"));

    assertTrue(result.success(), result.output());
    assertTrue(result.output().contains("a/match.java"), result.output());
    assertFalse(result.output().contains("b/match.java"));
  }

  @Test
  void prunesHiddenDirectories(@TempDir Path tmp) throws IOException {
    Files.createDirectory(tmp.resolve(".git"));
    Files.writeString(tmp.resolve(".git/HEAD"), "x", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("visible.txt"), "x", StandardCharsets.UTF_8);

    var result = GlobTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of("pattern", "*"));

    assertTrue(result.success(), result.output());
    assertTrue(result.output().contains("visible.txt"), result.output());
    assertFalse(result.output().contains("HEAD"));
  }

  @Test
  void missingPatternFails(@TempDir Path tmp) {
    var result = GlobTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of());
    assertFalse(result.success());
    assertTrue(result.output().contains("missing required 'pattern'"), result.output());
  }

  @Test
  void notADirectoryFails(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("file.txt"), "x", StandardCharsets.UTF_8);
    var result =
        GlobTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "*", "path", "file.txt"));
    assertFalse(result.success());
    assertTrue(result.output().contains("not a directory"), result.output());
  }

  @Test
  void invalidPatternFails(@TempDir Path tmp) {
    var result =
        GlobTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of("pattern", "[unclosed"));
    assertFalse(result.success());
    assertTrue(result.output().contains("Glob: invalid pattern"), result.output());
  }

  @Test
  void escapingWorkspaceFails(@TempDir Path tmp) {
    var result =
        GlobTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "*", "path", "../etc"));
    assertFalse(result.success());
    assertTrue(result.output().startsWith("Glob:"), result.output());
  }

  @Test
  void permissionKeyCarriesPath(@TempDir Path tmp) {
    var binding = GlobTool.binding(WorkspaceRoot.of(tmp));
    assertEquals(
        "src", binding.permissionKey(Map.of("pattern", "*", "path", "src")).canonicalArgs());
    assertEquals(".", binding.permissionKey(Map.of("pattern", "*")).canonicalArgs());
  }

  @Test
  void rejectsNullWorkspace() {
    assertThrows(NullPointerException.class, () -> GlobTool.binding(null));
  }
}
