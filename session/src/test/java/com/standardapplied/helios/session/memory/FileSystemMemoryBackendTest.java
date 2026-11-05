/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.memory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.session.files.SpecialFiles;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class FileSystemMemoryBackendTest {

  private static Path seed(Path workspace, String relPath, String content) throws IOException {
    var target = workspace.resolve(WorkspaceRoot.MEMORY_STORE).resolve(relPath);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content, StandardCharsets.UTF_8);
    return target;
  }

  @Test
  void viewReadsSeededFile(@TempDir Path tmp) throws IOException {
    seed(tmp, "user/profile.md", "hello");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertEquals("hello", backend.view("/memories/user/profile.md"));
  }

  @Test
  void viewMissingFails(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(NoSuchFileException.class, () -> backend.view("/memories/missing.md"));
  }

  @Test
  void viewRejectsBadPrefix(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(IllegalArgumentException.class, () -> backend.view("/elsewhere/x.md"));
  }

  @Test
  void viewRejectsBarePrefix(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(IllegalArgumentException.class, () -> backend.view("/memories/"));
  }

  @Test
  void viewRejectsDirectory(@TempDir Path tmp) throws IOException {
    var dir = tmp.resolve(WorkspaceRoot.MEMORY_STORE).resolve("subdir");
    Files.createDirectories(dir);
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var ex = assertThrows(IOException.class, () -> backend.view("/memories/subdir"));
    assertTrue(ex.getMessage().contains("not a regular file"), ex.getMessage());
  }

  @Test
  void listReturnsAllEntriesUnderPrefix(@TempDir Path tmp) throws IOException {
    seed(tmp, "INDEX.md", "1");
    seed(tmp, "user/a.md", "2");
    seed(tmp, "user/b.md", "3");
    seed(tmp, "project/c.md", "4");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));

    var all = backend.list("/memories/");
    assertEquals(
        List.of(
            "/memories/INDEX.md",
            "/memories/project/c.md",
            "/memories/user/a.md",
            "/memories/user/b.md"),
        all);

    var users = backend.list("/memories/user");
    assertEquals(List.of("/memories/user/a.md", "/memories/user/b.md"), users);
  }

  @Test
  void listOnSingleFileReturnsThatPath(@TempDir Path tmp) throws IOException {
    seed(tmp, "x.md", "x");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertEquals(List.of("/memories/x.md"), backend.list("/memories/x.md"));
  }

  @Test
  void listEmptyPrefixTreatedAsRoot(@TempDir Path tmp) throws IOException {
    seed(tmp, "a.md", "a");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertEquals(List.of("/memories/a.md"), backend.list(""));
  }

  @Test
  void listMissingMemoryRootReturnsEmpty(@TempDir Path tmp) throws IOException {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertEquals(List.of(), backend.list("/memories/"));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void listSkipsADirectoryItCannotOpen(@TempDir Path tmp) throws IOException {
    seed(tmp, "a.md", "a");
    var locked = seed(tmp, "locked/b.md", "b").getParent();
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
    try {
      Assumptions.assumeFalse(Files.isReadable(locked), "permissions are not enforced (root)");
      var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
      assertEquals(List.of("/memories/a.md"), backend.list("/memories/"));
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  void listMissingSubprefixReturnsEmpty(@TempDir Path tmp) throws IOException {
    seed(tmp, "user/a.md", "");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertEquals(List.of(), backend.list("/memories/project"));
  }

  @Test
  void listRejectsBadPrefix(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(IllegalArgumentException.class, () -> backend.list("/foo"));
  }

  @Test
  void listRejectsNullPrefix(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(NullPointerException.class, () -> backend.list(null));
  }

  @Test
  void viewRefusesPathEscape(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(RuntimeException.class, () -> backend.view("/memories/../escape.md"));
  }

  @Test
  void factoryRejectsNull() {
    assertThrows(NullPointerException.class, () -> FileSystemMemoryBackend.of(null));
  }

  @Test
  void accessorsExposeWorkspaceAndRoot(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    var backend = FileSystemMemoryBackend.of(ws);
    assertEquals(ws, backend.workspace());
    assertNotNull(backend.memoryRoot());
    assertTrue(backend.memoryRoot().toString().endsWith(WorkspaceRoot.MEMORY_STORE));
  }

  // ── write operations ──────────────────────────────────────────────────────

  @Test
  void createWritesNewEntry(@TempDir Path tmp) throws IOException {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    backend.create("/memories/user/profile.md", "alpha");
    assertEquals("alpha", backend.view("/memories/user/profile.md"));
  }

  @Test
  void createRefusesExistingEntry(@TempDir Path tmp) throws IOException {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    backend.create("/memories/x.md", "first");
    assertThrows(FileAlreadyExistsException.class, () -> backend.create("/memories/x.md", "again"));
  }

  @Test
  void createCreatesParentDirectories(@TempDir Path tmp) throws IOException {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    backend.create("/memories/deeply/nested/note.md", "hi");
    assertEquals("hi", backend.view("/memories/deeply/nested/note.md"));
  }

  @Test
  void createRejectsBadPath(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(IllegalArgumentException.class, () -> backend.create("/not-memories.md", "hi"));
  }

  @Test
  void createRejectsNullContent(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(NullPointerException.class, () -> backend.create("/memories/x.md", null));
  }

  @Test
  void strReplaceSwapsOccurrence(@TempDir Path tmp) throws IOException {
    seed(tmp, "x.md", "hello world");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    backend.strReplace("/memories/x.md", "world", "there");
    assertEquals("hello there", backend.view("/memories/x.md"));
  }

  @Test
  void strReplaceRefusesZeroMatches(@TempDir Path tmp) throws IOException {
    seed(tmp, "x.md", "abc");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var ex =
        assertThrows(IOException.class, () -> backend.strReplace("/memories/x.md", "zzz", "qqq"));
    assertTrue(ex.getMessage().contains("not found"), ex.getMessage());
  }

  @Test
  void strReplaceRefusesMultipleMatches(@TempDir Path tmp) throws IOException {
    seed(tmp, "x.md", "ab cd ab");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var ex =
        assertThrows(IOException.class, () -> backend.strReplace("/memories/x.md", "ab", "XX"));
    assertTrue(ex.getMessage().contains("more than once"), ex.getMessage());
  }

  @Test
  void strReplaceRefusesMissingFile(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(
        NoSuchFileException.class, () -> backend.strReplace("/memories/missing.md", "a", "b"));
  }

  @Test
  void strReplaceRefusesEmptyOldString(@TempDir Path tmp) throws IOException {
    seed(tmp, "x.md", "abc");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(
        IllegalArgumentException.class, () -> backend.strReplace("/memories/x.md", "", "X"));
  }

  @Test
  void strReplaceRejectsNullStrings(@TempDir Path tmp) throws IOException {
    seed(tmp, "x.md", "abc");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(NullPointerException.class, () -> backend.strReplace("/memories/x.md", null, "X"));
    assertThrows(
        NullPointerException.class, () -> backend.strReplace("/memories/x.md", "abc", null));
  }

  @Test
  void insertAddsLineAtPosition(@TempDir Path tmp) throws IOException {
    seed(tmp, "x.md", "one\ntwo\nthree\n");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    backend.insert("/memories/x.md", 2, "ZERO");
    assertEquals("one\nZERO\ntwo\nthree\n", backend.view("/memories/x.md"));
  }

  @Test
  void insertBeforeFirstLine(@TempDir Path tmp) throws IOException {
    seed(tmp, "x.md", "one\ntwo\n");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    backend.insert("/memories/x.md", 1, "HEADER");
    assertEquals("HEADER\none\ntwo\n", backend.view("/memories/x.md"));
  }

  @Test
  void insertAppendAfterLastLine(@TempDir Path tmp) throws IOException {
    seed(tmp, "x.md", "one\ntwo\n");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    backend.insert("/memories/x.md", 3, "FOOTER");
    assertEquals("one\ntwo\nFOOTER\n", backend.view("/memories/x.md"));
  }

  @Test
  void insertIntoEmptyFile(@TempDir Path tmp) throws IOException {
    seed(tmp, "x.md", "");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    backend.insert("/memories/x.md", 1, "first line");
    assertEquals("first line\n", backend.view("/memories/x.md"));
  }

  @Test
  void insertRejectsOutOfRange(@TempDir Path tmp) throws IOException {
    seed(tmp, "x.md", "one\ntwo\n");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(IllegalArgumentException.class, () -> backend.insert("/memories/x.md", 0, "neg"));
    assertThrows(
        IllegalArgumentException.class, () -> backend.insert("/memories/x.md", 99, "huge"));
  }

  @Test
  void insertRefusesMissingFile(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(NoSuchFileException.class, () -> backend.insert("/memories/missing.md", 1, "hi"));
  }

  @Test
  void insertHandlesContentWithTrailingNewline(@TempDir Path tmp) throws IOException {
    seed(tmp, "x.md", "one\n");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    backend.insert("/memories/x.md", 2, "two\n");
    assertEquals("one\ntwo\n", backend.view("/memories/x.md"));
  }

  @Test
  void deleteRemovesFile(@TempDir Path tmp) throws IOException {
    var seeded = seed(tmp, "x.md", "bye");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    backend.delete("/memories/x.md");
    assertFalse(Files.exists(seeded));
  }

  @Test
  void deleteRefusesMissingFile(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(NoSuchFileException.class, () -> backend.delete("/memories/nope.md"));
  }

  @Test
  void deleteRefusesDirectory(@TempDir Path tmp) throws IOException {
    Files.createDirectories(tmp.resolve(WorkspaceRoot.MEMORY_STORE + "/subdir"));
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var ex = assertThrows(IOException.class, () -> backend.delete("/memories/subdir"));
    assertTrue(ex.getMessage().contains("directory"), ex.getMessage());
  }

  // ── symlink confinement ───────────────────────────────────────────────────

  private static Path memoryDir(Path workspace) throws IOException {
    return Files.createDirectories(workspace.resolve(WorkspaceRoot.MEMORY_STORE));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void createUnderAncestorLinkToOutsideCreatesNothingOutside(@TempDir Path tmp) throws IOException {
    var root = Files.createDirectory(tmp.resolve("ws"));
    var outside = Files.createDirectory(tmp.resolve("outside"));
    Files.createSymbolicLink(memoryDir(root).resolve("proj"), outside);
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(root));
    assertThrows(RuntimeException.class, () -> backend.create("/memories/proj/new.md", "leak"));
    assertThrows(
        RuntimeException.class, () -> backend.create("/memories/proj/deeper/new.md", "leak"));
    try (var entries = Files.list(outside)) {
      assertEquals(List.of(), entries.toList());
    }
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void memoryRootThatIsItselfALinkIsRefused(@TempDir Path tmp) throws IOException {
    var root = Files.createDirectory(tmp.resolve("ws"));
    var outside = Files.createDirectory(tmp.resolve("outside"));
    Files.createDirectories(root.resolve(".agent"));
    Files.createSymbolicLink(root.resolve(WorkspaceRoot.MEMORY_STORE), outside);
    Files.writeString(outside.resolve("secret.md"), "s", StandardCharsets.UTF_8);
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(root));
    assertThrows(RuntimeException.class, () -> backend.create("/memories/new.md", "leak"));
    assertThrows(RuntimeException.class, () -> backend.view("/memories/secret.md"));
    assertThrows(RuntimeException.class, () -> backend.list(""));
    assertFalse(Files.exists(outside.resolve("new.md")));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void linkInsideWorkspaceButOutsideMemoryIsRefused(@TempDir Path tmp) throws IOException {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var project = tmp.resolve("project.txt");
    Files.writeString(project, "keep me\n", StandardCharsets.UTF_8);
    Files.createSymbolicLink(memoryDir(tmp).resolve("alias.md"), project);
    Files.createSymbolicLink(memoryDir(tmp).resolve("dir"), tmp);
    assertThrows(RuntimeException.class, () -> backend.view("/memories/alias.md"));
    assertThrows(
        RuntimeException.class, () -> backend.strReplace("/memories/alias.md", "keep", "lose"));
    assertThrows(RuntimeException.class, () -> backend.insert("/memories/alias.md", 1, "x"));
    assertThrows(RuntimeException.class, () -> backend.delete("/memories/alias.md"));
    assertThrows(RuntimeException.class, () -> backend.view("/memories/dir/project.txt"));
    assertThrows(RuntimeException.class, () -> backend.create("/memories/dir/new.txt", "x"));
    assertThrows(RuntimeException.class, () -> backend.list("/memories/dir"));
    assertEquals("keep me\n", Files.readString(project, StandardCharsets.UTF_8));
    assertFalse(Files.exists(tmp.resolve("new.txt")));
    assertEquals(List.of(), backend.list(""));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void linksWithinMemoryAreRefusedTooAndListSkipsThem(@TempDir Path tmp) throws IOException {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    seed(tmp, "real.md", "real");
    Files.createSymbolicLink(memoryDir(tmp).resolve("alias.md"), memoryDir(tmp).resolve("real.md"));
    Files.createSymbolicLink(memoryDir(tmp).resolve("dangling.md"), memoryDir(tmp).resolve("gone"));
    assertThrows(RuntimeException.class, () -> backend.view("/memories/alias.md"));
    assertThrows(RuntimeException.class, () -> backend.delete("/memories/alias.md"));
    assertThrows(RuntimeException.class, () -> backend.view("/memories/dangling.md"));
    assertThrows(RuntimeException.class, () -> backend.create("/memories/dangling.md", "x"));
    assertEquals(List.of("/memories/real.md"), backend.list(""));
    assertEquals("real", backend.view("/memories/real.md"));
  }

  @Test
  void absoluteAndTraversalInputsAreRefused(@TempDir Path tmp) throws IOException {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    seed(tmp, "real.md", "real");
    Files.writeString(tmp.resolve("project.txt"), "p", StandardCharsets.UTF_8);
    assertThrows(RuntimeException.class, () -> backend.view("/memories/../../project.txt"));
    assertThrows(RuntimeException.class, () -> backend.create("/memories/../../new.txt", "x"));
    assertThrows(RuntimeException.class, () -> backend.list("/memories/.."));
    assertThrows(
        NoSuchFileException.class, () -> backend.view("/memories/" + tmp.resolve("project.txt")));
    assertFalse(Files.exists(tmp.resolve("new.txt")));
    assertEquals("real", backend.view("/memories/real.md"));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void specialFilesAreNotReadAsMemory(@TempDir Path tmp) throws IOException {
    var fifo = memoryDir(tmp).resolve("pipe.md");
    org.junit.jupiter.api.Assumptions.assumeTrue(SpecialFiles.mkfifo(fifo));
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(IOException.class, () -> backend.view("/memories/pipe.md"));
    assertThrows(IOException.class, () -> backend.strReplace("/memories/pipe.md", "a", "b"));
    assertEquals(List.of(), backend.list(""));
  }

  @Test
  void malformedUtf8IsRejectedAndLeftUntouched(@TempDir Path tmp) throws IOException {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var malformed = new byte[] {0x61, (byte) 0xff, 0x0a};
    var file = seed(tmp, "bad.md", "");
    Files.write(file, malformed);
    assertThrows(CharacterCodingException.class, () -> backend.view("/memories/bad.md"));
    assertThrows(
        CharacterCodingException.class, () -> backend.strReplace("/memories/bad.md", "a", "b"));
    assertThrows(CharacterCodingException.class, () -> backend.insert("/memories/bad.md", 1, "x"));
    assertArrayEquals(malformed, Files.readAllBytes(file));
  }

  @Test
  void unencodableContentIsRejectedBeforeTouchingTheFile(@TempDir Path tmp) throws IOException {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var file = seed(tmp, "note.md", "keep\n");
    var unpaired = "x\ud800y";
    assertThrows(
        CharacterCodingException.class, () -> backend.create("/memories/new.md", unpaired));
    assertFalse(Files.exists(file.resolveSibling("new.md")));
    assertThrows(
        CharacterCodingException.class,
        () -> backend.strReplace("/memories/note.md", "keep", unpaired));
    assertThrows(
        CharacterCodingException.class, () -> backend.insert("/memories/note.md", 1, unpaired));
    assertEquals("keep\n", Files.readString(file, StandardCharsets.UTF_8));
  }

  private static List<String> entries(Path directory) throws IOException {
    try (var names = Files.list(directory)) {
      return names.map(entry -> entry.getFileName().toString()).sorted().toList();
    }
  }

  private static String readOpened(InputStream opened) throws IOException {
    try (opened) {
      return new String(opened.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void strReplaceLeavesAReaderOpenedBeforeItOnTheOldContent(@TempDir Path tmp) throws IOException {
    var file = seed(tmp, "note.md", "old content");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var opened = Files.newInputStream(file);
    backend.strReplace("/memories/note.md", "old", "new");
    assertEquals("old content", readOpened(opened));
    assertEquals("new content", backend.view("/memories/note.md"));
    assertEquals(List.of("note.md"), entries(file.getParent()));
  }

  @Test
  void insertLeavesAReaderOpenedBeforeItOnTheOldContent(@TempDir Path tmp) throws IOException {
    var file = seed(tmp, "note.md", "old content");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var opened = Files.newInputStream(file);
    backend.insert("/memories/note.md", 1, "new line");
    assertEquals("old content", readOpened(opened));
    assertEquals("new line\nold content", backend.view("/memories/note.md"));
    assertEquals(List.of("note.md"), entries(file.getParent()));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void anUpdateThatCannotWriteKeepsTheOldFileAndLeavesNoTemporary(@TempDir Path tmp)
      throws IOException {
    var file = seed(tmp, "note.md", "old content");
    var directory = file.getParent();
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
    try {
      Assumptions.assumeFalse(Files.isWritable(directory), "permissions are not enforced (root)");
      var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
      assertThrows(IOException.class, () -> backend.strReplace("/memories/note.md", "old", "new"));
      assertThrows(IOException.class, () -> backend.insert("/memories/note.md", 1, "new"));
      assertEquals("old content", Files.readString(file, StandardCharsets.UTF_8));
      assertEquals(List.of("note.md"), entries(directory));
    } finally {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  void aReplaceThatCannotRenameDeletesItsTemporary(@TempDir Path tmp) throws IOException {
    var directory = seed(tmp, "taken/kept.md", "kept").getParent();
    var files = new MemoryFiles(WorkspaceRoot.of(tmp));
    var target = files.resolve("/memories/taken");
    var bytes = "new".getBytes(StandardCharsets.UTF_8);
    var failure = assertThrows(FileSystemException.class, () -> files.replace(target, bytes));
    assertTrue(failure.getFile().matches("\\.taken\\.[0-9a-f-]{36}\\.tmp"), failure.getFile());
    assertEquals(List.of("taken"), entries(directory.getParent()));
    assertEquals("kept", Files.readString(directory.resolve("kept.md"), StandardCharsets.UTF_8));
  }

  @Test
  void updatesWorkUnderTheLongestFileNames(@TempDir Path tmp) throws IOException {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    for (var name : List.of("a".repeat(255), "\ud83d\ude00".repeat(63) + "abc")) {
      var path = "/memories/" + name;
      backend.create(path, "old content");
      backend.strReplace(path, "old", "new");
      backend.insert(path, 1, "first");
      assertEquals("first\nnew content", backend.view(path));
    }
    assertEquals(2, entries(backend.memoryRoot()).size());
  }

  @Test
  void aFileOverTheLimitIsNeitherReadNorUpdated(@TempDir Path tmp) throws IOException {
    var file = seed(tmp, "big.md", "");
    var oversized = "a".repeat(FileSystemMemoryBackend.MAX_FILE_BYTES + 1);
    Files.writeString(file, oversized, StandardCharsets.UTF_8);
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    for (var failure :
        List.of(
            assertThrows(IOException.class, () -> backend.view("/memories/big.md")),
            assertThrows(IOException.class, () -> backend.strReplace("/memories/big.md", "a", "b")),
            assertThrows(IOException.class, () -> backend.insert("/memories/big.md", 1, "x")))) {
      assertEquals("memory entry exceeds 1048576 bytes: /memories/big.md", failure.getMessage());
    }
    assertEquals(oversized, Files.readString(file, StandardCharsets.UTF_8));
    assertEquals(List.of("big.md"), entries(file.getParent()));
  }

  @Test
  void contentOverTheLimitIsRefusedBeforeAnythingIsWritten(@TempDir Path tmp) throws IOException {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var limit = Integer.toString(FileSystemMemoryBackend.MAX_FILE_BYTES);
    var atLimit = "x" + "a".repeat(FileSystemMemoryBackend.MAX_FILE_BYTES - 1);
    backend.create("/memories/full.md", atLimit);
    assertEquals(atLimit, backend.view("/memories/full.md"));
    var oversized = "a".repeat(FileSystemMemoryBackend.MAX_FILE_BYTES + 1);
    var created =
        assertThrows(IOException.class, () -> backend.create("/memories/new.md", oversized));
    var replaced =
        assertThrows(IOException.class, () -> backend.strReplace("/memories/full.md", "x", "yy"));
    var inserted =
        assertThrows(IOException.class, () -> backend.insert("/memories/full.md", 1, "y"));
    for (var failure : List.of(created, replaced, inserted)) {
      assertTrue(failure.getMessage().contains(limit), failure.getMessage());
    }
    assertEquals(atLimit, backend.view("/memories/full.md"));
    assertEquals(List.of("full.md"), entries(backend.memoryRoot()));
  }
}
