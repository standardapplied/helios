/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceRootTest {

  @Test
  void ofProducesCanonicalRoot(@TempDir Path tmp) throws IOException {
    var ws = WorkspaceRoot.of(tmp);
    assertEquals(tmp.toRealPath(), ws.root());
    assertTrue(ws.confineSymlinks());
  }

  @Test
  void resolveSafeAcceptsRelativePath(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("hi.txt"), "x", StandardCharsets.UTF_8);
    var resolved = WorkspaceRoot.of(tmp).resolveSafe("hi.txt");
    assertEquals(tmp.toRealPath().resolve("hi.txt"), resolved);
  }

  @Test
  void resolveSafeAcceptsAbsoluteUnderRoot(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("hi.txt"), "x", StandardCharsets.UTF_8);
    var abs = tmp.resolve("hi.txt").toAbsolutePath().toString();
    var resolved = WorkspaceRoot.of(tmp).resolveSafe(abs);
    assertEquals(tmp.toRealPath().resolve("hi.txt"), resolved);
  }

  @Test
  void resolveSafeRefusesBlank(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    assertThrows(WorkspaceRoot.WorkspaceEscapeException.class, () -> ws.resolveSafe(""));
    assertThrows(WorkspaceRoot.WorkspaceEscapeException.class, () -> ws.resolveSafe("   "));
  }

  @Test
  void resolveSafeRefusesNull(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    assertThrows(NullPointerException.class, () -> ws.resolveSafe(null));
  }

  @Test
  void resolveSafeRefusesLexicalEscape(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    assertThrows(
        WorkspaceRoot.WorkspaceEscapeException.class, () -> ws.resolveSafe("../escape.txt"));
  }

  @Test
  void resolveSafeRefusesAbsoluteOutsideRoot(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    assertThrows(WorkspaceRoot.WorkspaceEscapeException.class, () -> ws.resolveSafe("/etc/passwd"));
  }

  @Test
  void resolveSafeRefusesInvalidPath(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    assertThrows(WorkspaceRoot.WorkspaceEscapeException.class, () -> ws.resolveSafe("foo\0bar"));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void resolveSafeRefusesSymlinkOutsideRootWhenConfined(@TempDir Path tmp) throws IOException {
    var outside = Files.createTempDirectory("outside-");
    try {
      Files.writeString(outside.resolve("secret.txt"), "x", StandardCharsets.UTF_8);
      Files.createSymbolicLink(tmp.resolve("link.txt"), outside.resolve("secret.txt"));
      var ws = WorkspaceRoot.of(tmp);
      assertThrows(WorkspaceRoot.WorkspaceEscapeException.class, () -> ws.resolveSafe("link.txt"));
    } finally {
      Files.deleteIfExists(outside.resolve("secret.txt"));
      Files.deleteIfExists(outside);
    }
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void resolveSafePermitsSymlinkOutsideRootWhenUnconfined(@TempDir Path tmp) throws IOException {
    var outside = Files.createTempDirectory("outside-");
    try {
      Files.writeString(outside.resolve("secret.txt"), "x", StandardCharsets.UTF_8);
      Files.createSymbolicLink(tmp.resolve("link.txt"), outside.resolve("secret.txt"));
      var ws = new WorkspaceRoot(tmp, false);
      var resolved = ws.resolveSafe("link.txt");
      assertNotNull(resolved);
      assertEquals(outside.toRealPath().resolve("secret.txt"), resolved);
      try (var in = ws.newInputStream(resolved)) {
        assertEquals("x", new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    } finally {
      Files.deleteIfExists(outside.resolve("secret.txt"));
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void relativizeReturnsPathRelativeToRoot(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    var inside = ws.root().resolve("nested/foo.txt");
    assertEquals(
        Path.of("nested/foo.txt").toString().replace('\\', '/'),
        ws.relativize(inside).replace('\\', '/'));
  }

  @Test
  void relativizeOfRootReturnsDot(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    assertEquals(".", ws.relativize(ws.root()));
  }

  @Test
  void relativizeRejectsNull(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    assertThrows(NullPointerException.class, () -> ws.relativize(null));
  }

  @Test
  void relativizeOfPathOutsideRootReturnsAbsoluteString(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    var outside = Path.of("/tmp/something/else");
    assertEquals(outside.toString(), ws.relativize(outside));
  }

  @Test
  void constructorRejectsNullRoot() {
    assertThrows(NullPointerException.class, () -> WorkspaceRoot.of(null));
  }

  @Test
  void constructorRejectsNonDirectory(@TempDir Path tmp) throws IOException {
    var file = tmp.resolve("not-a-dir");
    Files.writeString(file, "x", StandardCharsets.UTF_8);
    assertThrows(IllegalArgumentException.class, () -> WorkspaceRoot.of(file));
  }

  @Test
  void workspaceEscapeExceptionCarriesMessage() {
    var ex = new WorkspaceRoot.WorkspaceEscapeException("custom message");
    assertEquals("custom message", ex.getMessage());
  }

  @Test
  void resolveSafeAcceptsNonexistentLeafUnderExistingParents(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    assertEquals(ws.root().resolve("new/dir/leaf.txt"), ws.resolveSafe("new/dir/leaf.txt"));
  }

  @Test
  void resolveSafeAcceptsUnicodeNames(@TempDir Path tmp) throws IOException {
    var ws = WorkspaceRoot.of(tmp);
    Files.createDirectories(ws.root().resolve("résumé"));
    Files.writeString(ws.root().resolve("résumé/ファイル.txt"), "x", StandardCharsets.UTF_8);
    assertEquals(ws.root().resolve("résumé/ファイル.txt"), ws.resolveSafe("résumé/ファイル.txt"));
    assertEquals(ws.root().resolve("résumé/新しい.txt"), ws.resolveSafe("résumé/新しい.txt"));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void rootUnderSymlinkedAncestorIsCanonicalisedAndUsable(@TempDir Path tmp) throws IOException {
    var real = Files.createDirectories(tmp.resolve("real/ws"));
    Files.createSymbolicLink(tmp.resolve("alias"), tmp.resolve("real"));
    Files.writeString(real.resolve("hi.txt"), "x", StandardCharsets.UTF_8);
    var ws = WorkspaceRoot.of(tmp.resolve("alias/ws"));
    assertEquals(real.toRealPath(), ws.root());
    assertEquals(ws.root().resolve("hi.txt"), ws.resolveSafe("hi.txt"));
    assertEquals("hi.txt", ws.relativize(ws.resolveSafe("hi.txt")));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void newLeafUnderAncestorLinkToOutsideIsRefused(@TempDir Path tmp) throws IOException {
    var root = Files.createDirectory(tmp.resolve("ws"));
    var outside = Files.createDirectory(tmp.resolve("outside"));
    Files.createSymbolicLink(root.resolve("escape"), outside);
    var ws = WorkspaceRoot.of(root);
    assertThrows(
        WorkspaceRoot.WorkspaceEscapeException.class, () -> ws.resolveSafe("escape/new.txt"));
    assertThrows(
        WorkspaceRoot.WorkspaceEscapeException.class,
        () -> ws.resolveSafe("escape/deeper/new.txt"));
    assertFalse(Files.exists(outside.resolve("new.txt")));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void nestedInRootLinksResolveToCanonicalTarget(@TempDir Path tmp) throws IOException {
    var ws = WorkspaceRoot.of(tmp);
    var root = ws.root();
    Files.createDirectories(root.resolve("d"));
    Files.writeString(root.resolve("d/file.txt"), "x", StandardCharsets.UTF_8);
    Files.createDirectories(root.resolve("b"));
    Files.createSymbolicLink(root.resolve("b/c"), root.resolve("d"));
    Files.createSymbolicLink(root.resolve("a"), root.resolve("b"));
    assertEquals(root.resolve("d/file.txt"), ws.resolveSafe("a/c/file.txt"));
    assertEquals(root.resolve("d/new.txt"), ws.resolveSafe("a/c/new.txt"));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void danglingAndLoopingLinksAreRefused(@TempDir Path tmp) throws IOException {
    var ws = WorkspaceRoot.of(tmp);
    var root = ws.root();
    Files.createSymbolicLink(root.resolve("dangling"), root.resolve("missing"));
    Files.createSymbolicLink(root.resolve("loop"), root.resolve("loop"));
    assertThrows(WorkspaceRoot.WorkspaceEscapeException.class, () -> ws.resolveSafe("dangling"));
    assertThrows(
        WorkspaceRoot.WorkspaceEscapeException.class, () -> ws.resolveSafe("dangling/child.txt"));
    assertThrows(WorkspaceRoot.WorkspaceEscapeException.class, () -> ws.resolveSafe("loop"));
    assertThrows(
        WorkspaceRoot.WorkspaceEscapeException.class, () -> ws.resolveSafe("loop/child.txt"));
    assertThrows(
        WorkspaceRoot.WorkspaceEscapeException.class,
        () -> new WorkspaceRoot(tmp, false).resolveSafe("dangling"));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void rootAliasLinksStayInsideWhileParentLinksEscape(@TempDir Path tmp) throws IOException {
    var root = Files.createDirectory(tmp.resolve("ws"));
    Files.writeString(root.resolve("x.txt"), "x", StandardCharsets.UTF_8);
    Files.createSymbolicLink(root.resolve("self"), root);
    Files.createSymbolicLink(root.resolve("up"), tmp);
    var ws = WorkspaceRoot.of(root);
    assertEquals(ws.root().resolve("x.txt"), ws.resolveSafe("self/x.txt"));
    assertEquals(ws.root().resolve("x.txt"), ws.resolveSafe("self/self/x.txt"));
    assertThrows(WorkspaceRoot.WorkspaceEscapeException.class, () -> ws.resolveSafe("up/other"));
    assertEquals(ws.root().resolve("x.txt"), ws.resolveSafe("up/ws/x.txt"));
  }

  @Test
  void openPrimitivesRefusePathsNotResolvedThroughTheRoot(@TempDir Path tmp) throws IOException {
    var ws = WorkspaceRoot.of(tmp);
    Files.writeString(ws.root().resolve("hi.txt"), "x", StandardCharsets.UTF_8);
    assertThrows(IllegalArgumentException.class, () -> ws.attributes(Path.of("hi.txt")));
    assertThrows(
        IllegalArgumentException.class, () -> ws.newInputStream(ws.root().resolve("./hi.txt")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ws.newOutputStream(tmp.resolve("../elsewhere.txt").toAbsolutePath().normalize()));
    assertThrows(NullPointerException.class, () -> ws.attributes(null));
    assertTrue(ws.attributes(ws.resolveSafe("hi.txt")).isRegularFile());
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void leafSwappedForSymlinkAfterResolveIsNeverFollowed(@TempDir Path tmp) throws IOException {
    var root = Files.createDirectory(tmp.resolve("ws"));
    var sentinel = tmp.resolve("sentinel.txt");
    Files.writeString(sentinel, "SENTINEL", StandardCharsets.UTF_8);
    var ws = WorkspaceRoot.of(root);
    var target = ws.root().resolve("swap.txt");
    for (var i = 0; i < 50; i++) {
      Files.writeString(target, "inside", StandardCharsets.UTF_8);
      var resolved = ws.resolveSafe("swap.txt");
      assertEquals(target, resolved);
      Files.delete(target);
      Files.createSymbolicLink(target, sentinel);
      assertThrows(IOException.class, () -> ws.newInputStream(resolved).close());
      assertThrows(
          IOException.class,
          () ->
              ws.newOutputStream(
                      resolved, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                  .close());
      assertThrows(
          IOException.class,
          () ->
              ws.newOutputStream(resolved, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)
                  .close());
      assertTrue(ws.attributes(resolved).isSymbolicLink());
      Files.delete(target);
    }
    assertEquals("SENTINEL", Files.readString(sentinel, StandardCharsets.UTF_8));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void openPrimitivesRefuseUnresolvedAncestorLink(@TempDir Path tmp) throws IOException {
    var root = Files.createDirectory(tmp.resolve("ws"));
    var outside = Files.createDirectory(tmp.resolve("outside"));
    var secret = outside.resolve("secret.txt");
    Files.writeString(secret, "SENTINEL", StandardCharsets.UTF_8);
    Files.createSymbolicLink(root.resolve("escape"), outside);
    var ws = WorkspaceRoot.of(root);
    var unresolved = ws.root().resolve("escape/secret.txt");
    assertThrows(
        WorkspaceRoot.WorkspaceEscapeException.class, () -> ws.resolveSafe("escape/secret.txt"));
    assertThrows(IOException.class, () -> ws.attributes(unresolved));
    assertThrows(IOException.class, () -> ws.newInputStream(unresolved).close());
    assertThrows(
        IOException.class,
        () ->
            ws.newOutputStream(
                    unresolved, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                .close());
    assertThrows(
        IOException.class,
        () ->
            ws.newOutputStream(ws.root().resolve("escape/new.txt"), StandardOpenOption.CREATE_NEW)
                .close());
    assertEquals("SENTINEL", Files.readString(secret, StandardCharsets.UTF_8));
    assertFalse(Files.exists(outside.resolve("new.txt")));
  }

  @Test
  void openPrimitivesAcceptResolvedPathWithMissingParents(@TempDir Path tmp) throws IOException {
    var ws = WorkspaceRoot.of(tmp);
    var resolved = ws.resolveSafe("a/b/new.txt");
    Files.createDirectories(resolved.getParent());
    try (var out = ws.newOutputStream(resolved, StandardOpenOption.CREATE_NEW)) {
      out.write("x".getBytes(StandardCharsets.UTF_8));
    }
    assertTrue(ws.attributes(resolved).isRegularFile());
    try (var in = ws.newInputStream(resolved)) {
      assertEquals("x", new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void resolveSafeAcceptsAbsolutePathThroughRootAlias(@TempDir Path tmp) throws IOException {
    var real = Files.createDirectories(tmp.resolve("real/ws"));
    var alias = Files.createSymbolicLink(tmp.resolve("alias"), tmp.resolve("real"));
    var aliasedRoot = alias.resolve("ws");
    Files.writeString(real.resolve("hi.txt"), "x", StandardCharsets.UTF_8);
    var ws = WorkspaceRoot.of(aliasedRoot);
    assertEquals(real.toRealPath(), ws.root());
    assertEquals(
        ws.root().resolve("hi.txt"), ws.resolveSafe(aliasedRoot.resolve("hi.txt").toString()));
    assertEquals(
        ws.root().resolve("new.txt"), ws.resolveSafe(aliasedRoot.resolve("new.txt").toString()));
    assertEquals(
        ws.root().resolve("new/parent/file.txt"),
        ws.resolveSafe(aliasedRoot.resolve("new/parent/file.txt").toString()));
    assertEquals(ws.root().resolve("hi.txt"), ws.resolveSafe("hi.txt"));
    var trusted = new WorkspaceRoot(aliasedRoot, false);
    assertEquals(
        trusted.root().resolve("hi.txt"),
        trusted.resolveSafe(aliasedRoot.resolve("hi.txt").toString()));
  }

  @Test
  void outputStreamAcceptsTypedOptionArrayWithoutMutatingIt(@TempDir Path tmp) throws IOException {
    var ws = WorkspaceRoot.of(tmp);
    var target = ws.resolveSafe("typed-options.txt");
    var options = new StandardOpenOption[] {StandardOpenOption.CREATE_NEW};

    try (var out = ws.newOutputStream(target, options)) {
      out.write("created".getBytes(StandardCharsets.UTF_8));
    }

    assertEquals("created", Files.readString(target));
    assertEquals(1, options.length);
    assertEquals(StandardOpenOption.CREATE_NEW, options[0]);
  }

  @Test
  void outputStreamWithoutOptionsCreatesAndTruncates(@TempDir Path tmp) throws IOException {
    var ws = WorkspaceRoot.of(tmp);
    var target = ws.resolveSafe("default-options.txt");

    try (var out = ws.newOutputStream(target)) {
      out.write("original".getBytes(StandardCharsets.UTF_8));
    }
    try (var out = ws.newOutputStream(target)) {
      out.write("new".getBytes(StandardCharsets.UTF_8));
    }

    assertEquals("new", Files.readString(target));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void resolveSafeStillRefusesAbsolutePathOutsideRootThroughAlias(@TempDir Path tmp)
      throws IOException {
    var real = Files.createDirectories(tmp.resolve("real/ws"));
    var alias = Files.createSymbolicLink(tmp.resolve("alias"), tmp.resolve("real"));
    Files.writeString(tmp.resolve("real/sibling.txt"), "x", StandardCharsets.UTF_8);
    var ws = WorkspaceRoot.of(alias.resolve("ws"));
    var trusted = new WorkspaceRoot(real, false);
    for (var probe :
        new String[] {
          alias.resolve("sibling.txt").toString(),
          alias.resolve("ws/../sibling.txt").toString(),
          tmp.resolve("missing/ws/hi.txt").toString(),
          "/etc/passwd"
        }) {
      assertThrows(WorkspaceRoot.WorkspaceEscapeException.class, () -> ws.resolveSafe(probe));
      assertThrows(WorkspaceRoot.WorkspaceEscapeException.class, () -> trusted.resolveSafe(probe));
    }
  }
}
