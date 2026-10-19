/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import static com.standardapplied.helios.session.test.BindingCalls.keyOf;
import static com.standardapplied.helios.session.test.BindingCalls.withholding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.session.tools.ToolCategory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class GrepToolTest {

  @Test
  void findsMatchingLines(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("a.txt"), "hello world\nfoo bar\nhello again\n", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("b.txt"), "no match here\n", StandardCharsets.UTF_8);

    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "hello"), ToolContext.noop());

    assertTrue(result.succeeded(), result.output());
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
    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "ZZZ"), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    assertEquals("", result.output());
  }

  @Test
  void includeGlobFiltersFiles(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("good.java"), "TARGET here\n", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("skip.md"), "TARGET there\n", StandardCharsets.UTF_8);

    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "TARGET", "include", "*.java"), ToolContext.noop());

    assertTrue(result.succeeded(), result.output());
    assertTrue(result.output().contains("good.java"), result.output());
    assertFalse(result.output().contains("skip.md"));
  }

  @Test
  void prunesHiddenDirectories(@TempDir Path tmp) throws IOException {
    Files.createDirectory(tmp.resolve(".git"));
    Files.writeString(tmp.resolve(".git/HEAD"), "HEADER\n", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("visible.txt"), "HEADER\n", StandardCharsets.UTF_8);

    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "HEADER"), ToolContext.noop());

    assertTrue(result.succeeded(), result.output());
    assertTrue(result.output().contains("visible.txt"), result.output());
    assertFalse(result.output().contains("HEAD\n") && result.output().contains(".git"));
  }

  @Test
  void skipsBinaryFiles(@TempDir Path tmp) throws IOException {
    Files.write(tmp.resolve("data.bin"), new byte[] {0x00, 'T', 'A', 'R', 'G', 'E', 'T', '\n'});
    Files.writeString(tmp.resolve("text.txt"), "TARGET line\n", StandardCharsets.UTF_8);

    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "TARGET"), ToolContext.noop());

    assertTrue(result.succeeded(), result.output());
    assertTrue(result.output().contains("text.txt"), result.output());
    assertFalse(result.output().contains("data.bin"));
  }

  @Test
  void skipsLargeFiles(@TempDir Path tmp) throws IOException {
    var buf = new byte[2 * 1024 * 1024];
    java.util.Arrays.fill(buf, (byte) 'A');
    Files.write(tmp.resolve("big.txt"), buf);
    Files.writeString(tmp.resolve("small.txt"), "AAA\n", StandardCharsets.UTF_8);
    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "AAA"), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    assertTrue(result.output().contains("small.txt"), result.output());
    assertFalse(result.output().contains("big.txt"));
  }

  @Test
  void missingPatternFails(@TempDir Path tmp) {
    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of(), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().contains("missing required 'pattern'"), result.output());
  }

  @Test
  void invalidRegexFails(@TempDir Path tmp) {
    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "[unclosed"), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().contains("Grep: invalid regex"), result.output());
  }

  @Test
  void notADirectoryFails(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("file.txt"), "x", StandardCharsets.UTF_8);
    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "x", "path", "file.txt"), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().contains("not a directory"), result.output());
  }

  @Test
  void missingRootIsAnIoErrorScanningIt(@TempDir Path tmp) {
    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "x", "path", "absent"), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().startsWith("Grep: I/O error scanning absent: "), result.output());
  }

  @Test
  void escapingWorkspaceFails(@TempDir Path tmp) {
    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "x", "path", "../etc"), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().startsWith("Grep:"), result.output());
  }

  @Test
  void permissionKeyCarriesPath(@TempDir Path tmp) {
    var binding = GrepTool.binding(WorkspaceRoot.of(tmp));
    assertEquals("./src", keyOf(binding, Map.of("pattern", "x", "path", "src")).canonicalArgs());
    assertEquals(".", keyOf(binding, Map.of("pattern", "x")).canonicalArgs());
  }

  @Test
  void skipsMalformedUtf8(@TempDir Path tmp) throws IOException {
    Files.write(tmp.resolve("bad.txt"), new byte[] {(byte) 0xC3, 0x28, 'T', 'A', 'R', '\n'});
    Files.writeString(tmp.resolve("good.txt"), "TAR\n", StandardCharsets.UTF_8);
    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "TAR"), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
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
            .execute(Map.of("pattern", "needle", "include", "**/*.md"), ToolContext.noop());

    assertTrue(result.succeeded(), result.output());
    var out = result.output();
    assertTrue(out.contains("intro.md:1:needle"), "root .md must match include='**/*.md': " + out);
    assertFalse(out.contains("config.yaml"), "yaml must be filtered out: " + out);
  }

  @Test
  void rejectsNullWorkspace() {
    assertThrows(NullPointerException.class, () -> GrepTool.binding(null));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void neverReadsThroughSymlinks(@TempDir Path tmp) throws IOException {
    var root = Files.createDirectory(tmp.resolve("ws"));
    var outside = Files.createDirectory(tmp.resolve("outside"));
    Files.writeString(outside.resolve("secret.txt"), "SENTINEL\n", StandardCharsets.UTF_8);
    Files.writeString(root.resolve("inside.txt"), "SENTINEL inside\n", StandardCharsets.UTF_8);
    Files.createSymbolicLink(root.resolve("leak.txt"), outside.resolve("secret.txt"));
    Files.createSymbolicLink(root.resolve("leakdir"), outside);
    Files.createSymbolicLink(root.resolve("alias.txt"), root.resolve("inside.txt"));
    Files.createSymbolicLink(root.resolve("dangling.txt"), root.resolve("gone.txt"));
    Files.createSymbolicLink(root.resolve("loop.txt"), root.resolve("loop.txt"));

    var result =
        GrepTool.binding(WorkspaceRoot.of(root))
            .tool()
            .execute(Map.of("pattern", "SENTINEL"), ToolContext.noop());

    assertTrue(result.succeeded(), result.output());
    assertEquals("inside.txt:1:SENTINEL inside\n", result.output());
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void sizeCapAppliesToTheTargetNotTheLinkAttributes(@TempDir Path tmp) throws IOException {
    var buf = new byte[2 * 1024 * 1024];
    java.util.Arrays.fill(buf, (byte) 'A');
    Files.write(tmp.resolve("big.txt"), buf);
    Files.createSymbolicLink(tmp.resolve("b"), tmp.resolve("big.txt"));
    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "AAA"), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    assertEquals("", result.output());
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void skipsSpecialFilesWithoutBlocking(@TempDir Path tmp) throws IOException {
    Assumptions.assumeTrue(SpecialFiles.mkfifo(tmp.resolve("pipe.txt")));
    Files.writeString(tmp.resolve("a.txt"), "hello\n", StandardCharsets.UTF_8);
    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "hello"), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    assertEquals("a.txt:1:hello\n", result.output());
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aSymlinkedSearchRootIsSearchedAtItsCanonicalPathAndRefusedThroughTheLink(@TempDir Path tmp)
      throws IOException {
    var ws = WorkspaceRoot.of(tmp);
    Files.createDirectories(ws.root().resolve("real"));
    Files.writeString(ws.root().resolve("real/a.txt"), "hello\n", StandardCharsets.UTF_8);
    Files.createSymbolicLink(ws.root().resolve("alias"), ws.root().resolve("real"));
    var tool = GrepTool.binding(ws).tool();

    var canonical =
        tool.execute(Map.of("pattern", "hello", "path", ws.canonical("alias")), ToolContext.noop());
    assertEquals("real/a.txt:1:hello\n", canonical.output());

    var throughTheLink =
        tool.execute(Map.of("pattern", "hello", "path", "alias"), ToolContext.noop());
    assertEquals(
        "Grep: path is not canonical or changed after it was authorised: alias now resolves to"
            + " ./real",
        throughTheLink.output());
  }

  @Test
  void capsMatchesAtOneThousandWithinOneFileAndMarksTruncation(@TempDir Path tmp)
      throws IOException {
    Files.writeString(tmp.resolve("big.txt"), "hit\n".repeat(1001), StandardCharsets.UTF_8);
    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "hit"), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    assertTrue(
        result.output().endsWith("big.txt:1000:hit\n[truncated at 1000 matches]\n"),
        result.output());
    assertFalse(result.output().contains(":1001:"), result.output());
  }

  @Test
  void capsMatchesAtOneThousandAcrossFiles(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("a.txt"), "hit\n".repeat(999), StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("b.txt"), "hit\n".repeat(999), StandardCharsets.UTF_8);
    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "hit"), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    var lines = result.output().split("\n");
    assertEquals(1001, lines.length);
    assertEquals("[truncated at 1000 matches]", lines[1000]);
  }

  @Test
  void cancelledContextFindsNothing(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("a.txt"), "hit\n", StandardCharsets.UTF_8);
    var call = new CancellationToken();
    call.cancel("stop");
    var ctx =
        new ToolContext(
            SessionContext.forTesting("cancelled"), call, Duration.ofMinutes(1), key -> false);
    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of("pattern", "hit"), ctx);
    assertTrue(result.succeeded(), result.output());
    assertEquals("", result.output());
  }

  @Test
  void invalidIncludePatternFails(@TempDir Path tmp) {
    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "x", "include", "["), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().startsWith("Grep: invalid include pattern '[': "), result.output());
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void skipsUnreadableFilesSilently(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("a.txt"), "hit\n", StandardCharsets.UTF_8);
    var locked = Files.writeString(tmp.resolve("b.txt"), "hit\n", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
    try {
      Assumptions.assumeFalse(Files.isReadable(locked), "permissions are not enforced (root)");
      var result =
          GrepTool.binding(WorkspaceRoot.of(tmp))
              .tool()
              .execute(Map.of("pattern", "hit"), ToolContext.noop());
      assertTrue(result.succeeded(), result.output());
      assertEquals("a.txt:1:hit\n", result.output());
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-------"));
    }
  }

  @Test
  void aWithheldDirectoryIsNeverDescendedInto(@TempDir Path tmp) throws IOException {
    Files.createDirectories(tmp.resolve("secrets/sub"));
    Files.writeString(tmp.resolve("secrets/key"), "TOPSECRET\n", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("secrets/sub/c.txt"), "TOPSECRET\n", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("a.txt"), "TOP\n", StandardCharsets.UTF_8);
    var asked = new CopyOnWriteArrayList<String>();

    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(
                Map.of("pattern", "TOP", "path", "."),
                withholding(
                    key -> {
                      asked.add(key);
                      return key.equals("./secrets");
                    }));

    assertEquals("a.txt:1:TOP\n[withheld by permission policy: 1]\n", result.output());
    assertEquals(List.of("./a.txt", "./secrets"), asked.stream().sorted().toList());
  }

  @Test
  void aWithheldFileCountsWhateverItsSize(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("small.txt"), "TOPSECRET\n", StandardCharsets.UTF_8);
    Files.writeString(
        tmp.resolve("large.txt"), "TOPSECRET\n".repeat(200_000), StandardCharsets.UTF_8);

    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "TOP"), withholding(key -> true));

    assertEquals("[withheld by permission policy: 2]\n", result.output());
  }

  @Test
  void theWithheldLineFollowsTheTruncationMarker(@TempDir Path tmp) throws IOException {
    Files.createDirectories(tmp.resolve("first"));
    Files.createDirectories(tmp.resolve("second"));
    var firstListed = firstListedDirectory(tmp);
    var other = firstListed.equals("first") ? "second" : "first";
    Files.writeString(tmp.resolve(firstListed + "/denied.txt"), "hit\n", StandardCharsets.UTF_8);
    Files.writeString(
        tmp.resolve(other + "/big.txt"), "hit\n".repeat(1001), StandardCharsets.UTF_8);

    var result =
        GrepTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("pattern", "hit"), withholding(key -> key.equals("./" + firstListed)));

    assertTrue(
        result
            .output()
            .endsWith(
                "big.txt:1000:hit\n[truncated at 1000 matches]\n"
                    + "[withheld by permission policy: 1]\n"),
        result.output());
  }

  @Test
  void aWalkDoesNotFollowADirectorySwappedAfterItsCheck(@TempDir Path tmp) throws IOException {
    assertEquals(
        "[withheld by permission policy: 1]\n", grepSwappingPublicAfterChecking(tmp, "./public"));
  }

  @Test
  void aWalkDoesNotReadAFileWhoseDirectoryIsSwappedAfterItsCheck(@TempDir Path tmp)
      throws IOException {
    assertEquals(
        "[withheld by permission policy: 1]\n",
        grepSwappingPublicAfterChecking(tmp, "./public/key"));
  }

  /**
   * Greps a workspace holding {@code public/key} and a withheld {@code secrets/key}, moving {@code
   * public} out of the workspace for a link to {@code secrets} as soon as {@code trigger} has been
   * checked.
   */
  private static String grepSwappingPublicAfterChecking(Path tmp, String trigger)
      throws IOException {
    var root = Files.createDirectories(tmp.resolve("ws"));
    Files.createDirectories(root.resolve("public"));
    Files.createDirectories(root.resolve("secrets"));
    Files.writeString(root.resolve("public/key"), "SAFE\n", StandardCharsets.UTF_8);
    Files.writeString(root.resolve("secrets/key"), "TOPSECRET\n", StandardCharsets.UTF_8);
    Predicate<String> withheld =
        key -> {
          if (key.equals(trigger)) {
            swap(root.resolve("public"), tmp.resolve("moved"), root.resolve("secrets"));
          }
          return key.equals("./secrets");
        };
    return GrepTool.binding(WorkspaceRoot.of(root))
        .tool()
        .execute(Map.of("pattern", "[A-Z]", "path", "."), withholding(withheld))
        .output();
  }

  private static void swap(Path directory, Path aside, Path linkTarget) {
    try {
      Files.move(directory, aside);
      Files.createSymbolicLink(directory, linkTarget);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The directory of {@code dir} that a walk of it visits first. */
  private static String firstListedDirectory(Path dir) throws IOException {
    try (var entries = Files.newDirectoryStream(dir)) {
      return entries.iterator().next().getFileName().toString();
    }
  }
}
