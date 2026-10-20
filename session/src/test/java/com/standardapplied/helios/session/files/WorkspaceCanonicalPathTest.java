/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link WorkspaceRoot} as a path space: {@link WorkspaceRoot#canonical} spells every target one
 * way, {@link WorkspaceRoot#resolveCanonical} resolves that spelling only while it still names the
 * same target, and {@link WorkspaceRoot#admits} refuses a glob no spelling can match.
 */
final class WorkspaceCanonicalPathTest {

  @TempDir Path tmp;

  private Path root;
  private WorkspaceRoot workspace;

  @BeforeEach
  void fixture() throws IOException {
    root = Files.createDirectory(tmp.resolve("ws")).toRealPath();
    Files.createDirectories(root.resolve("secrets"));
    Files.writeString(root.resolve("secrets/key"), "TOPSECRET", StandardCharsets.UTF_8);
    Files.createDirectories(root.resolve("docs"));
    Files.writeString(root.resolve("docs/a.txt"), "a", StandardCharsets.UTF_8);
    Files.createSymbolicLink(root.resolve("link"), root.resolve("secrets"));
    workspace = WorkspaceRoot.of(root);
  }

  private String escape(String requested) {
    return assertThrows(
            WorkspaceRoot.WorkspaceEscapeException.class, () -> workspace.canonical(requested))
        .getMessage();
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "., .",
    "./, .",
    "docs, ./docs",
    "docs/, ./docs",
    "./docs/./a.txt, ./docs/a.txt",
    "docs//a.txt, ./docs/a.txt",
    "docs/../secrets/key, ./secrets/key",
    "secrets/key, ./secrets/key",
    "link/key, ./secrets/key",
    "link, ./secrets",
    "docs/missing.txt, ./docs/missing.txt"
  })
  void everySpellingOfATargetIsCanonicalisedToOne(String requested, String canonical) {
    assertEquals(canonical, workspace.canonical(requested));
    assertEquals(canonical, workspace.canonical(canonical));
  }

  @Test
  void anAbsolutePathInsideTheRootIsSpelledRelatively() {
    assertEquals("./secrets/key", workspace.canonical(root.resolve("secrets/key").toString()));
    assertEquals(".", workspace.canonical(root.toString()));
  }

  @Test
  void dotDotIsResolvedLexicallyInAnAbsolutePathAsInARelativeOne() throws IOException {
    Files.createSymbolicLink(root.resolve("up"), Files.createDirectory(tmp.resolve("outside")));
    Files.createDirectories(tmp.resolve("docs"));
    Files.writeString(tmp.resolve("docs/a.txt"), "outside", StandardCharsets.UTF_8);

    assertEquals("./docs/a.txt", workspace.canonical("up/../docs/a.txt"));
    assertEquals("./docs/a.txt", workspace.canonical(root + "/up/../docs/a.txt"));
  }

  @Test
  void eachEscapeIsRefusedWithItsMessage() throws IOException {
    Files.createSymbolicLink(root.resolve("dangling"), root.resolve("gone"));
    Files.createSymbolicLink(root.resolve("loop"), root.resolve("loop"));
    var outside = Files.createDirectory(tmp.resolve("outside"));
    Files.createSymbolicLink(root.resolve("out"), outside);

    assertEquals("path escapes workspace root: ../outside", escape("../outside"));
    assertEquals(
        "path escapes workspace root via symlink: " + outside.resolve("f"),
        escape(outside.resolve("f").toString()));
    assertTrue(
        escape("dangling").startsWith("path traverses a dangling or looping symlink: dangling: "));
    assertTrue(escape("loop").startsWith("path traverses a dangling or looping symlink: loop: "));
    assertEquals("path escapes workspace root via symlink: out/f", escape("out/f"));
    assertEquals("parent directory does not exist: ./missing/x", escape("missing/x"));
    assertEquals("parent directory does not exist: ./docs/a.txt/x", escape("docs/a.txt/x"));
    assertEquals("path must not be blank", escape(" "));
    assertTrue(escape("a\0b").startsWith("invalid path: "));
  }

  @Test
  void spellNamesTheRootDotAndAnOutsidePathAbsolutely() {
    assertEquals(".", workspace.spell(root));
    assertEquals("./docs/a.txt", workspace.spell(root.resolve("docs/a.txt")));
    assertEquals(tmp.resolve("x").toString(), workspace.spell(tmp.resolve("x")));
    assertEquals(
        "resolved must not be null",
        assertThrows(NullPointerException.class, () -> workspace.spell(null)).getMessage());
  }

  // ── resolveCanonical ─────────────────────────────────────────────────────

  @Test
  void aCanonicalPathResolvesToItsLexicalLocation() {
    assertEquals(root.resolve("docs/a.txt"), workspace.resolveCanonical("./docs/a.txt"));
    assertEquals(root, workspace.resolveCanonical("."));
    assertEquals(root.resolve("docs/new.txt"), workspace.resolveCanonical("./docs/new.txt"));
  }

  @Test
  void aSymlinkSwappedInAfterAuthorisationIsRefused() throws IOException {
    var authorised = workspace.canonical("docs/a.txt");
    Files.writeString(root.resolve("secrets/a.txt"), "TOPSECRET", StandardCharsets.UTF_8);
    Files.move(root.resolve("docs"), root.resolve("docs-aside"));
    Files.createSymbolicLink(root.resolve("docs"), root.resolve("secrets"));

    var refused =
        assertThrows(
            WorkspaceRoot.WorkspaceEscapeException.class,
            () -> workspace.resolveCanonical(authorised));
    assertEquals(
        "path is not canonical or changed after it was authorised: ./docs/a.txt now resolves to"
            + " ./secrets/a.txt",
        refused.getMessage());
  }

  private String refusedOnResolve(String canonical) {
    return assertThrows(
            WorkspaceRoot.WorkspaceEscapeException.class,
            () -> workspace.resolveCanonical(canonical))
        .getMessage();
  }

  @Test
  void resolveCanonicalRefusesWhatCanonicalRefuses() throws IOException {
    Files.createSymbolicLink(root.resolve("dangling"), root.resolve("gone"));
    assertEquals("path escapes workspace root: ../outside", refusedOnResolve("../outside"));
    assertTrue(
        refusedOnResolve("./dangling")
            .startsWith("path traverses a dangling or looping symlink: ./dangling: "));
    assertEquals(
        "path escapes workspace root via symlink: " + tmp, refusedOnResolve(tmp.toString()));
  }

  // ── trusted workspace ────────────────────────────────────────────────────

  @Test
  void aTrustedWorkspaceSpellsAnOutsideTargetByItsRealPathAndResolvesIt() throws IOException {
    var outside = Files.createDirectory(tmp.resolve("outside"));
    Files.writeString(outside.resolve("f"), "f", StandardCharsets.UTF_8);
    Files.createSymbolicLink(root.resolve("ext"), outside);
    var trusted = new WorkspaceRoot(root, false);

    var canonical = trusted.canonical("ext/f");

    assertEquals(outside.toRealPath().resolve("f").toString(), canonical);
    assertEquals(outside.toRealPath().resolve("f"), trusted.resolveCanonical(canonical));
  }

  @Test
  void aTrustedWorkspaceRefusesAnOutsidePathNoLinkLeadsTo() throws IOException {
    var outside = Files.createDirectory(tmp.resolve("outside"));
    var trusted = new WorkspaceRoot(root, false);
    assertThrows(
        WorkspaceRoot.WorkspaceEscapeException.class,
        () -> trusted.canonical(outside.resolve("f").toString()));
  }

  @Test
  void aTrustedLinkToTheFilesystemRootHasNoParentToCheck() throws IOException {
    Files.createSymbolicLink(root.resolve("top"), Path.of("/"));
    assertEquals("/", new WorkspaceRoot(root, false).canonical("top"));
  }

  // ── admits ───────────────────────────────────────────────────────────────

  @ParameterizedTest
  @ValueSource(
      strings = {".", "./**", "./secrets/**", "./**/secrets/**", "**", "**/x.*", "**.java", "./*"})
  void aGlobAnchoredAtTheRootWithProperSegmentsIsAdmitted(String glob) {
    assertTrue(workspace.admits(glob));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"secrets/**", "./a/../b", "./docs/", "./a/./b", ".//a", "/abs/**", "*", "a", "/"})
  void aGlobNoCanonicalPathCanMatchIsNotAdmitted(String glob) {
    assertFalse(workspace.admits(glob));
  }

  @Test
  void onlyATrustedWorkspaceAdmitsAnAbsoluteGlob() {
    assertTrue(new WorkspaceRoot(root, false).admits("/abs/**"));
    assertFalse(new WorkspaceRoot(root, false).admits("/abs/../x"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"<root>*/**", "<root>-old/**", "<parent>/?s/secrets/**", "/**/secrets"})
  void aTrustedWorkspaceAdmitsAnAbsoluteGlobThatCanMatchOutsideItsRoot(String glob) {
    var spelled = glob.replace("<root>", root.toString()).replace("<parent>", tmp.toString());

    assertTrue(new WorkspaceRoot(root, false).admits(spelled));
  }

  @ParameterizedTest
  @ValueSource(strings = {"<root>", "<root>/**", "<root>/secrets/*", "<root>/a?/b"})
  void aTrustedWorkspaceRefusesAnAbsoluteGlobInsideItsRootSpelledRelative(String glob) {
    assertFalse(new WorkspaceRoot(root, false).admits(glob.replace("<root>", root.toString())));
  }

  @Test
  void theExampleIsACanonicalPath() {
    assertEquals("./src/Main.java", workspace.example());
  }
}
