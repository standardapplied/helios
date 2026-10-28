/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.test.SessionStoreContract;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileSessionStoreTest extends SessionStoreContract {

  @TempDir Path temp;

  @Override
  protected SessionStore store() {
    return FileSessionStore.of(directory());
  }

  private Path directory() {
    return temp.resolve("conversations");
  }

  private Path file() {
    return directory().resolve(SID + ".jsonl");
  }

  @Test
  void eachBatchIsOneLineOfJson() throws IOException {
    var store = FileSessionStore.of(directory());
    store.append(SID, 0, List.of(added(Message.user("a")), added(Message.assistant("b"))));
    store.append(SID, 2, List.of(replaced(Message.user("s"))));

    var lines = Files.readAllLines(file());
    assertEquals(2, lines.size());
    assertEquals(
        "{\"seq\":3,\"entries\":[" + SessionEntryJson.write(replaced(Message.user("s"))) + "]}",
        lines.get(1));
    assertTrue(Files.readString(file()).endsWith("\n"));
  }

  @Test
  void textIsStoredAsUtf8WithOnlyALoneSurrogateEscaped() throws IOException {
    var store = FileSessionStore.of(directory());
    store.append(SID, 0, List.of(added(Message.user("é 😀 \uD800"))));

    assertTrue(Files.readString(file()).contains("é 😀 \\uD800"));
  }

  @Test
  void aTornLastLineIsIgnoredByLoadAndTruncatedByTheNextAppend() throws IOException {
    var store = FileSessionStore.of(directory());
    store.append(SID, 0, List.of(added(Message.user("a"))));
    var whole = Files.readString(file());
    Files.writeString(file(), "{\"seq\":2,\"entr", StandardOpenOption.APPEND);

    assertEquals(List.of(Message.user("a")), store.load(SID).history());
    assertEquals(1, store.load(SID).lastSeq());

    store.append(SID, 1, List.of(added(Message.user("b"))));

    var lines = Files.readAllLines(file());
    assertEquals(whole, lines.get(0) + "\n");
    assertEquals(2, lines.size());
    assertEquals(List.of(Message.user("a"), Message.user("b")), store.load(SID).history());
  }

  @Test
  void aCorruptLineFailsTheReadNamingTheFileAndTheLine() throws IOException {
    var store = FileSessionStore.of(directory());
    store.append(SID, 0, List.of(added(Message.user("a"))));
    Files.writeString(file(), "not a batch\n", StandardOpenOption.APPEND);

    assertLineTwoFailsEveryRead(store);
  }

  @Test
  void aLineOfMalformedUtf8FailsTheReadNamingTheFileAndTheLine() throws IOException {
    var store = FileSessionStore.of(directory());
    store.append(SID, 0, List.of(added(Message.user("a"))));
    store.append(SID, 1, List.of(added(Message.user("hello"))));
    var bytes = Files.readAllBytes(file());
    bytes[new String(bytes, UTF_8).indexOf("hello")] = (byte) 0xFF;
    Files.write(file(), bytes);

    assertLineTwoFailsEveryRead(store);
  }

  @Test
  void aLineThatBreaksTheSequenceFailsTheRead() throws IOException {
    var store = FileSessionStore.of(directory());
    store.append(SID, 0, List.of(added(Message.user("a"))));
    var line =
        SessionEntryJson.write(new SessionEntryJson.Batch(5, List.of(added(Message.user("b")))));
    Files.writeString(file(), line + "\n", StandardOpenOption.APPEND);

    var thrown = assertThrows(SessionStoreException.class, () -> store.entries(SID));

    assertEquals(
        "session file " + file() + " line 2 starts at sequence number 5, expected 2",
        thrown.getMessage());
  }

  @Test
  void onPosixTheDirectoryAndEachFileAreTheOwnersOnly() throws IOException {
    var store = FileSessionStore.of(directory());
    store.append(SID, 0, List.of(added(Message.user("a"))));

    assertEquals(
        "rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory())));
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file())));
  }

  @Test
  void withoutPosixAttributesNoPermissionsAreRequested() throws IOException {
    try (var zip = FileSystems.newFileSystem(temp.resolve("store.zip"), Map.of("create", "true"))) {
      assertEquals(0, FileSessionStore.permissions(zip, "rw-------").length);
    }
  }

  @Test
  void aSecondStoreOnTheSameDirectoryIsDetectedAsAnotherWriter() {
    var first = FileSessionStore.of(directory());
    var second = FileSessionStore.of(directory());
    first.append(SID, 0, List.of(added(Message.user("a"))));
    second.append(SID, 1, List.of(added(Message.user("b"))));

    assertThrows(
        SessionConflictException.class,
        () -> first.append(SID, 1, List.of(added(Message.user("c")))));
    assertThrows(
        SessionConflictException.class,
        () -> second.append(SID, 0, List.of(added(Message.user("c")))));
    first.append(SID, 2, List.of(added(Message.user("c"))));

    assertEquals(
        List.of(Message.user("a"), Message.user("b"), Message.user("c")),
        second.load(SID).history());
  }

  @Test
  void aFileRecreatedAtTheSameSizeByAnotherStoreIsDetectedAsAnotherWriter() {
    var first = FileSessionStore.of(directory());
    var second = FileSessionStore.of(directory());
    var two = List.of(added(Message.user("a")), added(Message.user("b")));
    var padding = lineLength(two) - lineLength(List.of(added(Message.user(""))));
    first.append(SID, 0, List.of(added(Message.user("x".repeat(padding)))));
    var size = file().toFile().length();
    second.delete(SID);
    second.append(SID, 0, two);
    assertEquals(size, file().toFile().length());

    assertThrows(
        SessionConflictException.class,
        () -> first.append(SID, 1, List.of(added(Message.user("c")))));
    assertEquals(two, first.entries(SID));
  }

  /**
   * Closing any descriptor of a file releases this process's {@code FileLock} on it, so a read that
   * ran during an append would let another process write: every read, of any store on the
   * directory, waits for the process lock an append holds.
   */
  @Test
  void aReadWaitsForTheProcessLockOnTheSessionsFile() {
    var store = FileSessionStore.of(directory());
    var other = FileSessionStore.of(directory());
    store.append(SID, 0, List.of(added(Message.user("a"))));
    var loaded = new CompletableFuture<StoredSession>();
    var listed = new CompletableFuture<List<SessionEntry>>();
    Thread loading;
    Thread listing;
    synchronized (store.lockOf(SID)) {
      loading = Thread.ofPlatform().start(() -> loaded.complete(store.load(SID)));
      listing = Thread.ofPlatform().start(() -> listed.complete(other.entries(SID)));
      Await.until("the load to wait", () -> loading.getState() == Thread.State.BLOCKED);
      Await.until("the listing to wait", () -> listing.getState() == Thread.State.BLOCKED);
      assertFalse(loaded.isDone() || listed.isDone());
    }

    assertEquals(1, Await.value("load", loaded).lastSeq());
    assertEquals(1, Await.value("entries", listed).size());
  }

  @Test
  void entriesAreTheWholeLogAndDeleteRemovesTheFile() {
    var store = FileSessionStore.of(directory());
    var log = List.of(added(Message.user("a")), replaced(Message.user("s")));
    store.append(SID, 0, log);

    assertEquals(log, store.entries(SID));
    assertTrue(store.delete(SID));
    assertFalse(Files.exists(file()));
    assertFalse(store.delete(SID));
    assertEquals(List.of(), store.entries(SID));
    store.append(SID, 0, List.of(added(Message.user("again"))));
    assertEquals(1, store.load(SID).lastSeq());
  }

  @Test
  void aRefusedAppendLeavesNoFileToDelete() {
    var store = FileSessionStore.of(directory());

    var refused =
        assertThrows(
            SessionConflictException.class,
            () -> store.append(SID, 3, List.of(added(Message.user("a")))));

    assertEquals(
        "session chat-1 was written by another writer: the append expected last sequence number"
            + " 3, the store holds 0",
        refused.getMessage());
    assertFalse(Files.exists(file()));
    assertFalse(store.delete(SID));
  }

  @Test
  void aSessionFileReachedThroughASymbolicLinkIsRefused() throws IOException {
    var store = FileSessionStore.of(directory());
    var elsewhere = Files.writeString(temp.resolve("elsewhere.jsonl"), "");
    Files.createSymbolicLink(file(), elsewhere);

    var reading = assertThrows(SessionStoreException.class, () -> store.load(SID));
    var appending =
        assertThrows(
            SessionStoreException.class,
            () -> store.append(SID, 0, List.of(added(Message.user("a")))));

    assertEquals("cannot read session file " + file(), reading.getMessage());
    assertEquals("cannot append to session file " + file(), appending.getMessage());
    assertEquals("", Files.readString(elsewhere));
  }

  @Test
  void aSessionFileThatCannotBeDeletedFailsTheDelete() throws IOException {
    var store = FileSessionStore.of(directory());
    Files.createDirectories(file().resolve("child"));

    var thrown = assertThrows(SessionStoreException.class, () -> store.delete(SID));

    assertEquals("cannot delete session file " + file(), thrown.getMessage());
    assertInstanceOf(IOException.class, thrown.getCause());
  }

  @Test
  void aDirectoryThatCannotBeCreatedFailsTheStore() throws IOException {
    var occupied = Files.writeString(temp.resolve("occupied"), "a file");

    var thrown = assertThrows(SessionStoreException.class, () -> FileSessionStore.of(occupied));

    assertEquals("cannot create session directory " + occupied, thrown.getMessage());
  }

  @Test
  void anExistingDirectoryIsUsedAsItIs() throws IOException {
    Files.createDirectories(directory());
    Files.setPosixFilePermissions(directory(), PosixFilePermissions.fromString("rwxr-x---"));

    FileSessionStore.of(directory()).append(SID, 0, List.of(added(Message.user("a"))));

    assertEquals(
        "rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory())));
    assertEquals(1, FileSessionStore.of(directory()).load(SID).lastSeq());
  }

  @Test
  void aFileHoldingOnlyATornLineLoadsEmpty() throws IOException {
    Files.createDirectories(directory());
    Files.write(file(), "{\"seq\":1".getBytes(UTF_8));
    var store = FileSessionStore.of(directory());

    assertEquals(new StoredSession(SID, 0, List.of()), store.load(SID));
    store.append(SID, 0, List.of(added(Message.user("a"))));
    assertEquals(1, Files.readAllLines(file()).size());
  }

  /** Both a load and another store's append fail, naming the file and line 2. */
  private void assertLineTwoFailsEveryRead(SessionStore store) {
    var other = FileSessionStore.of(directory());

    var thrown = assertThrows(SessionStoreException.class, () -> store.load(SID));
    var appending =
        assertThrows(
            SessionStoreException.class,
            () -> other.append(SID, 2, List.of(added(Message.user("b")))));

    assertEquals("session file " + file() + " line 2 is not a batch", thrown.getMessage());
    assertEquals(thrown.getMessage(), appending.getMessage());
  }

  private static int lineLength(List<SessionEntry> entries) {
    return SessionEntryJson.write(new SessionEntryJson.Batch(1, entries)).length();
  }
}
