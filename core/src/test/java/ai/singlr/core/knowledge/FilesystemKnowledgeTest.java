/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.singlr.core.common.SecretRegistry;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class FilesystemKnowledgeTest {

  private static FilesystemKnowledge plain(Path root) {
    return FilesystemKnowledge.builder(root).build();
  }

  private static Tool toolNamed(FilesystemKnowledge kb, String name) {
    return kb.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
  }

  @Test
  void rootMustExist() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FilesystemKnowledge.builder(Path.of("/nonexistent/zzz")).build());
  }

  @Test
  void rootIsExposed(@TempDir Path tmp) {
    var kb = plain(tmp);
    assertEquals(tmp.toAbsolutePath().normalize(), kb.root());
  }

  @Test
  void toolsListInDeclaredOrder(@TempDir Path tmp) {
    var kb = plain(tmp);
    var names = kb.tools().stream().map(Tool::name).toList();
    assertEquals(List.of("kb_grep", "kb_glob", "kb_read"), names);
  }

  @Test
  void privateRegistryIfNotConfigured(@TempDir Path tmp) {
    var kb = plain(tmp);
    assertNotNull(kb.secretRegistry());
    assertEquals(0, kb.secretRegistry().size());
  }

  @Test
  void sharedRegistryHonored(@TempDir Path tmp) {
    var registry = new SecretRegistry();
    var kb = FilesystemKnowledge.builder(tmp).withSecretRegistry(registry).build();
    assertSame(registry, kb.secretRegistry());
  }

  @Test
  void grepFindsMatchAcrossFiles(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("a.md"), "alpha line 1\nbeta line 2\n");
    Files.writeString(tmp.resolve("b.md"), "gamma line 1\nbeta line 2\n");
    var grep = toolNamed(plain(tmp), "kb_grep");
    var result = grep.execute(Map.of("pattern", "beta"));
    assertTrue(result.success());
    assertTrue(result.output().contains("a.md:2:beta line 2"));
    assertTrue(result.output().contains("b.md:2:beta line 2"));
  }

  @Test
  void grepMissingPatternFails(@TempDir Path tmp) {
    var grep = toolNamed(plain(tmp), "kb_grep");
    var result = grep.execute(Map.of());
    assertFalse(result.success());
  }

  @Test
  void grepInvalidPatternFails(@TempDir Path tmp) {
    var grep = toolNamed(plain(tmp), "kb_grep");
    var result = grep.execute(Map.of("pattern", "[unclosed"));
    assertFalse(result.success());
    assertTrue(result.output().contains("Invalid regular expression"));
  }

  @Test
  void grepInvalidGlobFails(@TempDir Path tmp) {
    var grep = toolNamed(plain(tmp), "kb_grep");
    var result = grep.execute(Map.of("pattern", "x", "glob", "[bad"));
    assertFalse(result.success());
    assertTrue(result.output().contains("Invalid glob"));
  }

  @Test
  void grepRespectsGlob(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("a.md"), "needle\n");
    Files.writeString(tmp.resolve("b.txt"), "needle\n");
    var grep = toolNamed(plain(tmp), "kb_grep");
    var result = grep.execute(Map.of("pattern", "needle", "glob", "**.md"));
    assertTrue(result.output().contains("a.md"));
    assertFalse(result.output().contains("b.txt"));
  }

  @Test
  void grepCappedAtConfiguredMax(@TempDir Path tmp) throws Exception {
    var sb = new StringBuilder();
    for (var i = 0; i < 50; i++) {
      sb.append("needle ").append(i).append('\n');
    }
    Files.writeString(tmp.resolve("a.md"), sb.toString());
    var kb = FilesystemKnowledge.builder(tmp).withMaxGrepResults(5).build();
    var grep = toolNamed(kb, "kb_grep");
    var result = grep.execute(Map.of("pattern", "needle"));
    assertTrue(result.output().contains("[capped at 5"));
    var matches = result.output().lines().filter(l -> l.contains("needle")).count();
    assertEquals(5, matches);
  }

  @Test
  void grepMaxResultsArgLowersCap(@TempDir Path tmp) throws Exception {
    var sb = new StringBuilder();
    for (var i = 0; i < 20; i++) {
      sb.append("needle\n");
    }
    Files.writeString(tmp.resolve("a.md"), sb.toString());
    var kb = FilesystemKnowledge.builder(tmp).withMaxGrepResults(15).build();
    var grep = toolNamed(kb, "kb_grep");
    var result = grep.execute(Map.of("pattern", "needle", "max_results", 3));
    assertTrue(result.output().contains("[capped at 3"));
  }

  @Test
  void grepNoMatchReportsNothing(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("a.md"), "alpha\n");
    var grep = toolNamed(plain(tmp), "kb_grep");
    var result = grep.execute(Map.of("pattern", "missing"));
    assertTrue(result.output().contains("(no matches)"));
  }

  @Test
  void grepSkipsBinaryFiles(@TempDir Path tmp) throws Exception {
    var bin = new byte[] {'n', 'e', 'e', 'd', 'l', 'e', 0, 'r', 'e', 's', 't'};
    Files.write(tmp.resolve("blob.bin"), bin);
    Files.writeString(tmp.resolve("text.md"), "needle\n");
    var grep = toolNamed(plain(tmp), "kb_grep");
    var result = grep.execute(Map.of("pattern", "needle"));
    assertTrue(result.output().contains("text.md"));
    assertFalse(result.output().contains("blob.bin"));
  }

  @Test
  void grepSkipsHiddenDirsByDefault(@TempDir Path tmp) throws Exception {
    var hidden = Files.createDirectory(tmp.resolve(".git"));
    Files.writeString(hidden.resolve("config"), "[user]\nname = needle\n");
    Files.writeString(tmp.resolve("readme.md"), "needle\n");
    var grep = toolNamed(plain(tmp), "kb_grep");
    var result = grep.execute(Map.of("pattern", "needle"));
    assertTrue(result.output().contains("readme.md"));
    assertFalse(result.output().contains(".git"));
  }

  @Test
  void grepSeesHiddenWhenSkipHiddenDisabled(@TempDir Path tmp) throws Exception {
    var hidden = Files.createDirectory(tmp.resolve(".dir"));
    Files.writeString(hidden.resolve("inner"), "needle\n");
    var kb = FilesystemKnowledge.builder(tmp).withSkipHidden(false).build();
    var grep = toolNamed(kb, "kb_grep");
    var result = grep.execute(Map.of("pattern", "needle"));
    assertTrue(result.output().contains(".dir"));
  }

  @Test
  void grepRespectsCustomPathFilter(@TempDir Path tmp) throws Exception {
    Files.createDirectory(tmp.resolve("excluded"));
    Files.writeString(tmp.resolve("excluded").resolve("a.md"), "needle\n");
    Files.writeString(tmp.resolve("included.md"), "needle\n");
    var kb =
        FilesystemKnowledge.builder(tmp)
            .withPathFilter(p -> !p.toString().contains("excluded"))
            .build();
    var grep = toolNamed(kb, "kb_grep");
    var result = grep.execute(Map.of("pattern", "needle"));
    assertTrue(result.output().contains("included.md"));
    assertFalse(result.output().contains("excluded"));
  }

  @Test
  void grepSkipsFilesLargerThanMax(@TempDir Path tmp) throws Exception {
    var big = new byte[2048];
    java.util.Arrays.fill(big, (byte) 'n');
    Files.write(tmp.resolve("big.md"), big);
    Files.writeString(tmp.resolve("small.md"), "n\n");
    var kb = FilesystemKnowledge.builder(tmp).withMaxFileSize(1024).build();
    var grep = toolNamed(kb, "kb_grep");
    var result = grep.execute(Map.of("pattern", "n"));
    assertTrue(result.output().contains("small.md"));
    assertFalse(result.output().contains("big.md"));
  }

  @Test
  void grepSkipsSymlinks(@TempDir Path tmp) throws Exception {
    var target = tmp.resolve("target.md");
    Files.writeString(target, "needle\n");
    var link = tmp.resolve("link.md");
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException unsupported) {
      assumeTrue(false, "symlinks not supported");
    }
    var grep = toolNamed(plain(tmp), "kb_grep");
    var result = grep.execute(Map.of("pattern", "needle"));
    var matches = result.output().lines().filter(l -> l.contains("needle")).count();
    assertEquals(1, matches, "should only find target.md, not the symlink");
  }

  @Test
  void grepRedactsSecretInOutput(@TempDir Path tmp) throws Exception {
    var registry = new SecretRegistry();
    registry.register("DB_PASS", "supersecret123");
    Files.writeString(tmp.resolve("config.md"), "password=supersecret123\n");
    var kb = FilesystemKnowledge.builder(tmp).withSecretRegistry(registry).build();
    var grep = toolNamed(kb, "kb_grep");
    var result = grep.execute(Map.of("pattern", "password"));
    assertFalse(result.output().contains("supersecret123"));
    assertTrue(result.output().contains("<redacted:DB_PASS>"));
  }

  @Test
  void grepTimeoutFiresAndAddsMarker(@TempDir Path tmp) throws Exception {
    for (var i = 0; i < 200; i++) {
      Files.writeString(tmp.resolve("f-" + i + ".md"), "needle " + i + "\n");
    }
    var kb = FilesystemKnowledge.builder(tmp).withGrepTimeout(Duration.ofNanos(1)).build();
    var grep = toolNamed(kb, "kb_grep");
    var result = grep.execute(Map.of("pattern", "needle"));
    assertTrue(result.output().contains("[grep timed out"));
  }

  @Test
  void globReturnsMatches(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("a.md"), "x");
    Files.writeString(tmp.resolve("b.md"), "x");
    Files.writeString(tmp.resolve("c.txt"), "x");
    var glob = toolNamed(plain(tmp), "kb_glob");
    var result = glob.execute(Map.of("pattern", "**.md"));
    assertTrue(result.output().contains("a.md"));
    assertTrue(result.output().contains("b.md"));
    assertFalse(result.output().contains("c.txt"));
  }

  @Test
  void globMissingPatternFails(@TempDir Path tmp) {
    var glob = toolNamed(plain(tmp), "kb_glob");
    var result = glob.execute(Map.of());
    assertFalse(result.success());
  }

  @Test
  void globEmptyPatternFails(@TempDir Path tmp) {
    var glob = toolNamed(plain(tmp), "kb_glob");
    var result = glob.execute(Map.of("pattern", ""));
    assertFalse(result.success());
  }

  @Test
  void globInvalidPatternFails(@TempDir Path tmp) {
    var glob = toolNamed(plain(tmp), "kb_glob");
    var result = glob.execute(Map.of("pattern", "[broken"));
    assertFalse(result.success());
  }

  @Test
  void globNoMatchReports(@TempDir Path tmp) {
    var glob = toolNamed(plain(tmp), "kb_glob");
    var result = glob.execute(Map.of("pattern", "**.never"));
    assertTrue(result.output().contains("(no matches)"));
  }

  @Test
  void globCappedAtMax(@TempDir Path tmp) throws Exception {
    for (var i = 0; i < 10; i++) {
      Files.writeString(tmp.resolve("f-" + i + ".md"), "x");
    }
    var kb = FilesystemKnowledge.builder(tmp).withMaxGlobResults(3).build();
    var glob = toolNamed(kb, "kb_glob");
    var result = glob.execute(Map.of("pattern", "**.md"));
    assertTrue(result.output().contains("[capped at 3"));
  }

  @Test
  void globSkipsHiddenAndSymlinks(@TempDir Path tmp) throws Exception {
    var hiddenDir = Files.createDirectory(tmp.resolve(".dir"));
    Files.writeString(hiddenDir.resolve("h.md"), "x");
    Files.writeString(tmp.resolve("visible.md"), "x");
    var target = tmp.resolve("target.md");
    Files.writeString(target, "x");
    try {
      Files.createSymbolicLink(tmp.resolve("link.md"), target);
    } catch (UnsupportedOperationException ignored) {
      // skip symlink branch
    }
    var glob = toolNamed(plain(tmp), "kb_glob");
    var result = glob.execute(Map.of("pattern", "**.md"));
    assertTrue(result.output().contains("visible.md"));
    assertFalse(result.output().contains(".dir"));
    assertFalse(result.output().contains("link.md"));
  }

  @Test
  void globRespectsCustomPathFilter(@TempDir Path tmp) throws Exception {
    Files.createDirectory(tmp.resolve("dropme"));
    Files.writeString(tmp.resolve("dropme").resolve("a.md"), "x");
    Files.writeString(tmp.resolve("keepme.md"), "x");
    var kb =
        FilesystemKnowledge.builder(tmp)
            .withPathFilter(p -> !p.toString().contains("dropme"))
            .build();
    var glob = toolNamed(kb, "kb_glob");
    var result = glob.execute(Map.of("pattern", "**.md"));
    assertTrue(result.output().contains("keepme.md"));
    assertFalse(result.output().contains("dropme"));
  }

  @Test
  void readReturnsWholeFile(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("a.md"), "line one\nline two\nline three\n");
    var read = toolNamed(plain(tmp), "kb_read");
    var result = read.execute(Map.of("path", "a.md"));
    assertTrue(result.success());
    assertTrue(result.output().contains("line one"));
    assertTrue(result.output().contains("line three"));
  }

  @Test
  void readMissingPathFails(@TempDir Path tmp) {
    var read = toolNamed(plain(tmp), "kb_read");
    var result = read.execute(Map.of());
    assertFalse(result.success());
  }

  @Test
  void readNonexistentFails(@TempDir Path tmp) {
    var read = toolNamed(plain(tmp), "kb_read");
    var result = read.execute(Map.of("path", "missing.md"));
    assertFalse(result.success());
    assertTrue(result.output().contains("not found"));
  }

  @Test
  void readRefusesAbsolutePath(@TempDir Path tmp) {
    var read = toolNamed(plain(tmp), "kb_read");
    var result = read.execute(Map.of("path", "/etc/passwd"));
    assertFalse(result.success());
  }

  @Test
  void readRefusesParentTraversal(@TempDir Path tmp) {
    var read = toolNamed(plain(tmp), "kb_read");
    var result = read.execute(Map.of("path", "../escape"));
    assertFalse(result.success());
  }

  @Test
  void readRefusesNulByte(@TempDir Path tmp) {
    var read = toolNamed(plain(tmp), "kb_read");
    var result = read.execute(Map.of("path", "a\0b"));
    assertFalse(result.success());
  }

  @Test
  void readRefusesSymlinkOutsideRoot(@TempDir Path tmp) throws Exception {
    var outside = Files.createTempDirectory("kb-outside-");
    try {
      var secret = outside.resolve("secret");
      Files.writeString(secret, "secret-content-12345");
      try {
        Files.createSymbolicLink(tmp.resolve("link"), secret);
      } catch (UnsupportedOperationException unsupported) {
        assumeTrue(false, "symlinks not supported");
      }
      var read = toolNamed(plain(tmp), "kb_read");
      var result = read.execute(Map.of("path", "link"));
      assertFalse(result.success());
      assertTrue(result.output().contains("Symlinks"));
    } finally {
      try (var walk = Files.walk(outside)) {
        walk.sorted(java.util.Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (java.io.IOException ignored) {
                  }
                });
      }
    }
  }

  @Test
  void readRefusesDirectoryNamedAsFile(@TempDir Path tmp) throws Exception {
    Files.createDirectory(tmp.resolve("subdir"));
    var read = toolNamed(plain(tmp), "kb_read");
    var result = read.execute(Map.of("path", "subdir"));
    assertFalse(result.success());
    assertTrue(result.output().contains("Not a regular file"));
  }

  @Test
  void readRefusesFileLargerThanMax(@TempDir Path tmp) throws Exception {
    var big = new byte[3000];
    java.util.Arrays.fill(big, (byte) 'a');
    Files.write(tmp.resolve("big.md"), big);
    var kb = FilesystemKnowledge.builder(tmp).withMaxFileSize(1024).build();
    var read = toolNamed(kb, "kb_read");
    var result = read.execute(Map.of("path", "big.md"));
    assertFalse(result.success());
    assertTrue(result.output().contains("exceeds maximum size"));
  }

  @Test
  void readHonorsLineRange(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("a.md"), "one\ntwo\nthree\nfour\n");
    var read = toolNamed(plain(tmp), "kb_read");
    var result = read.execute(Map.of("path", "a.md", "start_line", 2, "end_line", 3));
    assertTrue(result.success());
    assertEquals("two\nthree\n", result.output());
  }

  @Test
  void readClipsEndLinePastEof(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("a.md"), "one\ntwo\n");
    var read = toolNamed(plain(tmp), "kb_read");
    var result = read.execute(Map.of("path", "a.md", "start_line", 1, "end_line", 100));
    assertTrue(result.success());
    assertEquals("one\ntwo\n", result.output());
  }

  @Test
  void readStartPastEofIsEmpty(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("a.md"), "one\ntwo\n");
    var read = toolNamed(plain(tmp), "kb_read");
    var result = read.execute(Map.of("path", "a.md", "start_line", 100));
    assertTrue(result.success());
    assertEquals("", result.output());
  }

  @Test
  void readNegativeLinesRejected(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("a.md"), "one\n");
    var read = toolNamed(plain(tmp), "kb_read");
    assertFalse(read.execute(Map.of("path", "a.md", "start_line", 0)).success());
    assertFalse(read.execute(Map.of("path", "a.md", "end_line", 0)).success());
  }

  @Test
  void readEndBeforeStartRejected(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("a.md"), "one\ntwo\n");
    var read = toolNamed(plain(tmp), "kb_read");
    var result = read.execute(Map.of("path", "a.md", "start_line", 5, "end_line", 2));
    assertFalse(result.success());
  }

  @Test
  void readTruncatesPastMaxBytes(@TempDir Path tmp) throws Exception {
    var sb = new StringBuilder();
    for (var i = 0; i < 1000; i++) {
      sb.append("line").append(i).append('\n');
    }
    Files.writeString(tmp.resolve("a.md"), sb.toString());
    var kb = FilesystemKnowledge.builder(tmp).withMaxBytesPerRead(1024).build();
    var read = toolNamed(kb, "kb_read");
    var result = read.execute(Map.of("path", "a.md"));
    assertTrue(result.output().contains("[truncated"));
    assertTrue(result.output().length() < 2048);
  }

  @Test
  void readRedactsSecretInFile(@TempDir Path tmp) throws Exception {
    var registry = new SecretRegistry();
    registry.register("API_KEY", "sk_live_abc12345");
    Files.writeString(tmp.resolve("env.md"), "API_KEY=sk_live_abc12345\n");
    var kb = FilesystemKnowledge.builder(tmp).withSecretRegistry(registry).build();
    var read = toolNamed(kb, "kb_read");
    var result = read.execute(Map.of("path", "env.md"));
    assertFalse(result.output().contains("sk_live_abc12345"));
    assertTrue(result.output().contains("<redacted:API_KEY>"));
  }

  @Test
  void readRedactsSecretInFailureMessage(@TempDir Path tmp) {
    var registry = new SecretRegistry();
    registry.register("WHATEVER", "alphabetagamma");
    var kb = FilesystemKnowledge.builder(tmp).withSecretRegistry(registry).build();
    var read = toolNamed(kb, "kb_read");
    var result = read.execute(Map.of("path", "alphabetagamma-missing.md"));
    assertFalse(result.success());
    assertFalse(result.output().contains("alphabetagamma"));
    assertTrue(result.output().contains("<redacted:WHATEVER>"));
  }

  @Test
  void builderRejectsTooSmallMaxFileSize(@TempDir Path tmp) {
    assertThrows(
        IllegalArgumentException.class,
        () -> FilesystemKnowledge.builder(tmp).withMaxFileSize(100));
  }

  @Test
  void builderRejectsTooSmallMaxBytesPerRead(@TempDir Path tmp) {
    assertThrows(
        IllegalArgumentException.class,
        () -> FilesystemKnowledge.builder(tmp).withMaxBytesPerRead(100));
  }

  @Test
  void builderRejectsZeroMaxGrepResults(@TempDir Path tmp) {
    assertThrows(
        IllegalArgumentException.class,
        () -> FilesystemKnowledge.builder(tmp).withMaxGrepResults(0));
  }

  @Test
  void builderRejectsZeroMaxGlobResults(@TempDir Path tmp) {
    assertThrows(
        IllegalArgumentException.class,
        () -> FilesystemKnowledge.builder(tmp).withMaxGlobResults(0));
  }

  @Test
  void builderRejectsNullTimeout(@TempDir Path tmp) {
    assertThrows(
        IllegalArgumentException.class,
        () -> FilesystemKnowledge.builder(tmp).withGrepTimeout(null));
  }

  @Test
  void builderRejectsZeroTimeout(@TempDir Path tmp) {
    assertThrows(
        IllegalArgumentException.class,
        () -> FilesystemKnowledge.builder(tmp).withGrepTimeout(Duration.ZERO));
  }

  @Test
  void builderRejectsNegativeTimeout(@TempDir Path tmp) {
    assertThrows(
        IllegalArgumentException.class,
        () -> FilesystemKnowledge.builder(tmp).withGrepTimeout(Duration.ofSeconds(-1)));
  }

  @Test
  void grepZeroMaxResultsArgIgnored(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("a.md"), "needle\nneedle\n");
    var grep = toolNamed(plain(tmp), "kb_grep");
    var result = grep.execute(Map.of("pattern", "needle", "max_results", 0));
    assertTrue(result.success());
    var matches = result.output().lines().filter(l -> l.contains("needle")).count();
    assertEquals(2, matches);
  }

  @Test
  void grepNegativeMaxResultsArgIgnored(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("a.md"), "needle\n");
    var grep = toolNamed(plain(tmp), "kb_grep");
    var result = grep.execute(Map.of("pattern", "needle", "max_results", -5));
    assertTrue(result.success());
    assertTrue(result.output().contains("needle"));
  }

  @Test
  void redactionMarkerSurvivesRedactionResultEdgeCase(@TempDir Path tmp) throws Exception {
    var registry = new SecretRegistry();
    registry.register("X", "12345678");
    Files.writeString(tmp.resolve("a.md"), "before12345678after\n");
    var kb = FilesystemKnowledge.builder(tmp).withSecretRegistry(registry).build();
    var read = toolNamed(kb, "kb_read");
    var result = read.execute(Map.of("path", "a.md"));
    assertEquals("before<redacted:X>after\n", result.output());
  }

  @Test
  void grepWithPatternThatErrorsCleanly(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("a.md"), "x\n");
    var grep = toolNamed(plain(tmp), "kb_grep");
    var result = grep.execute(Map.of("pattern", 12345));
    assertFalse(result.success());
  }

  @Test
  void readWithIntStartLineCoercesProperly(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("a.md"), "one\ntwo\nthree\n");
    var read = toolNamed(plain(tmp), "kb_read");
    var result = read.execute(Map.of("path", "a.md", "start_line", 2));
    assertTrue(result.success());
    assertTrue(result.output().contains("two"));
    assertFalse(result.output().contains("one"));
  }

  @Test
  void grepWithPermissivePathFilter(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("a.md"), "needle\n");
    var kb = FilesystemKnowledge.builder(tmp).withPathFilter(p -> true).build();
    var grep = toolNamed(kb, "kb_grep");
    var result = grep.execute(Map.of("pattern", "needle"));
    assertTrue(result.output().contains("a.md"));
  }

  @Test
  void globWithPermissivePathFilter(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("a.md"), "x");
    var kb = FilesystemKnowledge.builder(tmp).withPathFilter(p -> true).build();
    var glob = toolNamed(kb, "kb_glob");
    var result = glob.execute(Map.of("pattern", "**.md"));
    assertTrue(result.output().contains("a.md"));
  }

  @Test
  void grepPathFilterAtFileLevel(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("keep.md"), "needle\n");
    Files.writeString(tmp.resolve("drop.md"), "needle\n");
    var kb =
        FilesystemKnowledge.builder(tmp)
            .withPathFilter(p -> !p.getFileName().toString().equals("drop.md"))
            .build();
    var grep = toolNamed(kb, "kb_grep");
    var result = grep.execute(Map.of("pattern", "needle"));
    assertTrue(result.output().contains("keep.md"));
    assertFalse(result.output().contains("drop.md"));
  }

  @Test
  void globPathFilterAtFileLevel(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("keep.md"), "x");
    Files.writeString(tmp.resolve("drop.md"), "x");
    var kb =
        FilesystemKnowledge.builder(tmp)
            .withPathFilter(p -> !p.getFileName().toString().equals("drop.md"))
            .build();
    var glob = toolNamed(kb, "kb_glob");
    var result = glob.execute(Map.of("pattern", "**.md"));
    assertTrue(result.output().contains("keep.md"));
    assertFalse(result.output().contains("drop.md"));
  }

  @Test
  void grepTimeoutFiresInsideSingleFileScan(@TempDir Path tmp) throws Exception {
    var sb = new StringBuilder(2_000_000);
    for (var i = 0; i < 200_000; i++) {
      sb.append("line ").append(i).append('\n');
    }
    Files.writeString(tmp.resolve("huge.md"), sb.toString());
    var kb =
        FilesystemKnowledge.builder(tmp)
            .withMaxFileSize(5_000_000)
            .withGrepTimeout(Duration.ofMillis(1))
            .build();
    var grep = toolNamed(kb, "kb_grep");
    var result = grep.execute(Map.of("pattern", "neverMatches"));
    assertTrue(result.output().contains("[grep timed out"));
  }

  @Test
  void grepEmptyGlobTreatedAsNoFilter(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("a.md"), "needle\n");
    var grep = toolNamed(plain(tmp), "kb_grep");
    var result = grep.execute(Map.of("pattern", "needle", "glob", ""));
    assertTrue(result.output().contains("a.md"));
  }

  @Test
  void grepSkipsTopLevelHiddenFile(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve(".hidden"), "needle\n");
    Files.writeString(tmp.resolve("visible.md"), "needle\n");
    var grep = toolNamed(plain(tmp), "kb_grep");
    var result = grep.execute(Map.of("pattern", "needle"));
    assertTrue(result.output().contains("visible.md"));
    assertFalse(result.output().contains(".hidden"));
  }

  @Test
  void globSkipsTopLevelHiddenFile(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve(".hidden"), "x");
    Files.writeString(tmp.resolve("visible.md"), "x");
    var glob = toolNamed(plain(tmp), "kb_glob");
    var result = glob.execute(Map.of("pattern", "*"));
    assertTrue(result.output().contains("visible.md"));
    assertFalse(result.output().contains(".hidden"));
  }

  @Test
  void grepTimeoutFiresInsideLargeFile(@TempDir Path tmp) throws Exception {
    var sb = new StringBuilder();
    for (var i = 0; i < 5000; i++) {
      sb.append("needle ").append(i).append('\n');
    }
    Files.writeString(tmp.resolve("big.md"), sb.toString());
    var kb = FilesystemKnowledge.builder(tmp).withGrepTimeout(Duration.ofNanos(1)).build();
    var grep = toolNamed(kb, "kb_grep");
    var result = grep.execute(Map.of("pattern", "needle"));
    assertTrue(result.output().contains("[grep timed out"));
  }

  @Test
  void crossToolRedactionViaSharedRegistry(@TempDir Path tmp) throws Exception {
    var registry = new SecretRegistry();
    registry.register("TOK", "tokentokentoken");
    Files.writeString(tmp.resolve("a.md"), "value=tokentokentoken\n");
    var kb = FilesystemKnowledge.builder(tmp).withSecretRegistry(registry).build();
    for (var t : kb.tools()) {
      ToolResult r =
          switch (t.name()) {
            case "kb_grep" -> t.execute(Map.of("pattern", "value"));
            case "kb_glob" -> t.execute(Map.of("pattern", "**.md"));
            default -> t.execute(Map.of("path", "a.md"));
          };
      assertFalse(r.output().contains("tokentokentoken"), t.name() + " leaked secret");
    }
  }

  /**
   * Live agent-session tests caught this on first run: an agent that reaches for {@code
   * **&#47;*.md} (the natural pattern, also the one our own tool description uses as an example)
   * got "no markdown files" against a corpus with root-level markdown files. JDK's NIO glob
   * requires at least one directory segment after {@code **&#47;}. Every other glob system agents
   * have seen (ripgrep, gitignore, Python pathlib) treats it as recursive-including-root. {@link
   * FilesystemKnowledge#compileGlob} normalises to that semantic.
   */
  @Test
  void kbGlobMatchesRootFilesWithRecursivePattern(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("intro.md"), "# Intro\n");
    Files.writeString(tmp.resolve("guide.md"), "# Guide\n");
    Files.createDirectory(tmp.resolve("nested"));
    Files.writeString(tmp.resolve("nested").resolve("deep.md"), "# Deep\n");
    Files.writeString(tmp.resolve("config.yaml"), "k: v\n");

    var kb = plain(tmp);
    var result = toolNamed(kb, "kb_glob").execute(Map.of("pattern", "**/*.md"));
    assertTrue(result.success(), result.output());
    var out = result.output();
    assertTrue(out.contains("intro.md"), "root file intro.md must match **/*.md: " + out);
    assertTrue(out.contains("guide.md"), "root file guide.md must match **/*.md: " + out);
    assertTrue(out.contains("deep.md"), "nested file must still match **/*.md: " + out);
    assertFalse(out.contains("config.yaml"), "yaml file must not match **/*.md: " + out);
  }

  @Test
  void kbGlobNonRecursivePatternMatchesOnlyRoot(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("root.md"), "x");
    Files.createDirectory(tmp.resolve("sub"));
    Files.writeString(tmp.resolve("sub").resolve("nested.md"), "x");

    var kb = plain(tmp);
    // Bare *.md (without **/) must NOT match nested files — only the root form. Pin the
    // semantic so a future "be helpful" extension doesn't make every pattern recursive.
    var result = toolNamed(kb, "kb_glob").execute(Map.of("pattern", "*.md"));
    assertTrue(result.success(), result.output());
    var out = result.output();
    assertTrue(out.contains("root.md"), out);
    assertFalse(out.contains("nested.md"), "*.md must not match nested files: " + out);
  }

  @Test
  void kbGrepGlobArgMatchesRootFilesToo(@TempDir Path tmp) throws Exception {
    // Same fix applies to kb_grep's optional `glob` argument — without it, an agent that runs
    // kb_grep with glob="**/*.md" against a root-only corpus would see zero matches.
    Files.writeString(tmp.resolve("notes.md"), "alpha\nbeta gamma\n");
    var kb = plain(tmp);
    var result = toolNamed(kb, "kb_grep").execute(Map.of("pattern", "beta", "glob", "**/*.md"));
    assertTrue(result.success(), result.output());
    assertTrue(
        result.output().contains("notes.md"),
        "kb_grep with glob=**/*.md must match root markdown files: " + result.output());
  }
}
