/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.SecretRegistry;
import ai.singlr.session.tools.ToolCategory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GrepToolTest {

  @Test
  void findsMatchingLines(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("a.txt"), "hello world\nfoo bar\nhello again\n", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("b.txt"), "no match here\n", StandardCharsets.UTF_8);

    var result = GrepTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of("pattern", "hello"));

    assertTrue(result.success(), result.output());
    var out = result.output();
    assertTrue(out.contains("a.txt:1:hello world"), out);
    assertTrue(out.contains("a.txt:3:hello again"), out);
    assertFalse(out.contains("b.txt"));
  }

  @Test
  void categoryIsSearch(@TempDir Path tmp) {
    var binding = GrepTool.binding(WorkspaceRoot.of(tmp));
    assertEquals(ToolCategory.SEARCH, binding.category());
    assertEquals("Grep", binding.name());
    assertEquals("Grep", GrepTool.NAME);
  }

  @Test
  void emptyResultWhenNoMatch(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("a.txt"), "nothing\n", StandardCharsets.UTF_8);
    var result = GrepTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of("pattern", "ZZZ"));
    assertTrue(result.success(), result.output());
    assertEquals("", result.output());
  }

  @Test
  void includeGlobFiltersFiles(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("good.java"), "TARGET here\n", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("skip.md"), "TARGET there\n", StandardCharsets.UTF_8);

    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "TARGET", "include", "*.java"));

    assertTrue(result.success(), result.output());
    assertTrue(result.output().contains("good.java"), result.output());
    assertFalse(result.output().contains("skip.md"));
  }

  @Test
  void prunesHiddenDirectories(@TempDir Path tmp) throws IOException {
    Files.createDirectory(tmp.resolve(".git"));
    Files.writeString(tmp.resolve(".git/HEAD"), "HEADER\n", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("visible.txt"), "HEADER\n", StandardCharsets.UTF_8);

    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of("pattern", "HEADER"));

    assertTrue(result.success(), result.output());
    assertTrue(result.output().contains("visible.txt"), result.output());
    assertFalse(result.output().contains("HEAD\n") && result.output().contains(".git"));
  }

  @Test
  void skipsBinaryFiles(@TempDir Path tmp) throws IOException {
    Files.write(tmp.resolve("data.bin"), new byte[] {0x00, 'T', 'A', 'R', 'G', 'E', 'T', '\n'});
    Files.writeString(tmp.resolve("text.txt"), "TARGET line\n", StandardCharsets.UTF_8);

    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of("pattern", "TARGET"));

    assertTrue(result.success(), result.output());
    assertTrue(result.output().contains("text.txt"), result.output());
    assertFalse(result.output().contains("data.bin"));
  }

  @Test
  void skipsLargeFiles(@TempDir Path tmp) throws IOException {
    var buf = new byte[2 * 1024 * 1024];
    java.util.Arrays.fill(buf, (byte) 'A');
    Files.write(tmp.resolve("big.txt"), buf);
    Files.writeString(tmp.resolve("small.txt"), "AAA\n", StandardCharsets.UTF_8);
    var result = GrepTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of("pattern", "AAA"));
    assertTrue(result.success(), result.output());
    assertTrue(result.output().contains("small.txt"), result.output());
    assertFalse(result.output().contains("big.txt"));
  }

  @Test
  void missingPatternFails(@TempDir Path tmp) {
    var result = GrepTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of());
    assertFalse(result.success());
    assertTrue(result.output().contains("missing required 'pattern'"), result.output());
  }

  @Test
  void invalidRegexFails(@TempDir Path tmp) {
    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of("pattern", "[unclosed"));
    assertFalse(result.success());
    assertTrue(result.output().contains("Grep: invalid regex"), result.output());
  }

  @Test
  void notADirectoryFails(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("file.txt"), "x", StandardCharsets.UTF_8);
    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "x", "path", "file.txt"));
    assertFalse(result.success());
    assertTrue(result.output().contains("not a directory"), result.output());
  }

  @Test
  void escapingWorkspaceFails(@TempDir Path tmp) {
    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "x", "path", "../etc"));
    assertFalse(result.success());
    assertTrue(result.output().startsWith("Grep:"), result.output());
  }

  @Test
  void permissionKeyCarriesPath(@TempDir Path tmp) {
    var binding = GrepTool.binding(WorkspaceRoot.of(tmp));
    assertEquals(
        "src", binding.permissionKey(Map.of("pattern", "x", "path", "src")).canonicalArgs());
    assertEquals(".", binding.permissionKey(Map.of("pattern", "x")).canonicalArgs());
  }

  @Test
  void skipsMalformedUtf8(@TempDir Path tmp) throws IOException {
    Files.write(tmp.resolve("bad.txt"), new byte[] {(byte) 0xC3, 0x28, 'T', 'A', 'R', '\n'});
    Files.writeString(tmp.resolve("good.txt"), "TAR\n", StandardCharsets.UTF_8);
    var result = GrepTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of("pattern", "TAR"));
    assertTrue(result.success(), result.output());
    assertTrue(result.output().contains("good.txt"), result.output());
    assertFalse(result.output().contains("bad.txt"));
  }

  @Test
  void includeGlobRecursivePatternMatchesRootFiles(@TempDir Path tmp) throws IOException {
    // Same regression as GlobTool: '**/*.md' must match root-level intro.md, not silently skip it.
    Files.writeString(tmp.resolve("intro.md"), "needle\n", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("config.yaml"), "needle\n", StandardCharsets.UTF_8);

    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "needle", "include", "**/*.md"));

    assertTrue(result.success(), result.output());
    var out = result.output();
    assertTrue(out.contains("intro.md:1:needle"), "root .md must match include='**/*.md': " + out);
    assertFalse(out.contains("config.yaml"), "yaml must be filtered out: " + out);
  }

  @Test
  void rejectsNullWorkspace() {
    assertThrows(NullPointerException.class, () -> GrepTool.binding(null));
    assertThrows(NullPointerException.class, () -> GrepTool.binding(null, null));
  }

  @Test
  void redactorOverloadScrubsMatchContentButLeavesPath(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("config.txt"),
        "user=alice\napi_key=ghp_supersecrettoken_xyz\n",
        StandardCharsets.UTF_8);
    var registry = new SecretRegistry();
    registry.register("GH_TOKEN", "ghp_supersecrettoken_xyz");

    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp), registry.redactor())
            .tool()
            .execute(Map.of("pattern", "api_key"));

    assertTrue(result.success(), result.output());
    var out = result.output();
    assertFalse(out.contains("ghp_supersecrettoken_xyz"), "secret leaked: " + out);
    assertTrue(out.contains("<redacted:GH_TOKEN>"), "marker missing: " + out);
    assertTrue(out.contains("config.txt:2:"), "path prefix should remain: " + out);
  }

  @Test
  void redactorNullEquivalentToOneArgBinding(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("a.txt"), "hello\n", StandardCharsets.UTF_8);
    var withNull =
        GrepTool.binding(WorkspaceRoot.of(tmp), null).tool().execute(Map.of("pattern", "hello"));
    var oneArg = GrepTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of("pattern", "hello"));
    assertTrue(withNull.success());
    assertTrue(oneArg.success());
    assertEquals(oneArg.output(), withNull.output());
  }
}
