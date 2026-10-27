/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link SessionStore} that keeps each session in a JSON-lines file, {@code <sessionId>.jsonl},
 * in one directory. Each line is one batch, {@code {"seq":<first sequence number>,"entries":[…]}}
 * with each entry in {@link SessionEntryJson}'s form, ended by {@code \n}: a line is the unit of
 * atomicity. On a POSIX file system the directory is created {@code rwx------} and each file {@code
 * rw-------}, since a conversation is user data; a file is never opened through a symbolic link.
 *
 * <p>{@link #append} holds a lock of this process on the session's file and an exclusive {@link
 * java.nio.channels.FileLock} on it, so a second process writing the same directory is detected,
 * not corrupted. It takes the last sequence number from what it last read or wrote while the file
 * still has that size, and otherwise reads the file; it writes the batch in one write and forces it
 * to the device before it returns. A last line without its {@code \n} is a write that did not
 * finish: {@link #load} ignores it and the next {@code append} truncates it. Any other line that is
 * not a batch, or does not continue the sequence, fails the read, naming the file and the line.
 */
public final class FileSessionStore implements SessionStore {

  /** The locks of this process on session files, shared by every instance and picked by path. */
  private static final Object[] FILE_LOCKS = newLocks(64);

  private static final String SUFFIX = ".jsonl";

  private final Path directory;
  private final FileAttribute<?>[] fileAttributes;
  private final Map<String, SessionFile.Tail> tails = new ConcurrentHashMap<>();

  private FileSessionStore(Path directory, FileAttribute<?>[] fileAttributes) {
    this.directory = directory;
    this.fileAttributes = fileAttributes;
  }

  /**
   * A store over {@code directory}, created when missing.
   *
   * @param directory the directory holding the session files; non-null
   * @return the store
   * @throws NullPointerException if {@code directory} is null
   * @throws SessionStoreException if the directory cannot be created or resolved
   */
  public static FileSessionStore of(Path directory) {
    Objects.requireNonNull(directory, "directory must not be null");
    var fileSystem = directory.getFileSystem();
    try {
      Files.createDirectories(directory, permissions(fileSystem, "rwx------"));
      return new FileSessionStore(directory.toRealPath(), permissions(fileSystem, "rw-------"));
    } catch (IOException e) {
      throw new SessionStoreException("cannot create session directory " + directory, e);
    }
  }

  @Override
  public StoredSession load(String sessionId) {
    var log = file(sessionId).read();
    return StoredSession.fold(sessionId, log.lastSeq(), log.entries());
  }

  @Override
  public void append(String sessionId, long expectedLastSeq, List<SessionEntry> entries) {
    var batch = AppendArguments.batch(sessionId, entries);
    var file = file(sessionId);
    synchronized (lockOf(sessionId)) {
      try {
        tails.put(sessionId, file.append(sessionId, expectedLastSeq, batch, tails.get(sessionId)));
      } catch (SessionStoreException e) {
        tails.remove(sessionId);
        throw e;
      }
    }
  }

  /**
   * The session's whole log, in order.
   *
   * @param sessionId the session id; valid per {@link SessionStore#requireValidId}
   * @return the log; empty for a session never written
   * @throws SessionStoreException if the file cannot be read or holds a line that is not a batch
   */
  public List<SessionEntry> entries(String sessionId) {
    return file(sessionId).read().entries();
  }

  /**
   * Remove a session's file.
   *
   * @param sessionId the session id; valid per {@link SessionStore#requireValidId}
   * @return whether the file existed
   * @throws SessionStoreException if the file cannot be deleted
   */
  public boolean delete(String sessionId) {
    var path = path(sessionId);
    synchronized (lockOf(sessionId)) {
      tails.remove(sessionId);
      try {
        return Files.deleteIfExists(path);
      } catch (IOException e) {
        throw new SessionStoreException("cannot delete session file " + path, e);
      }
    }
  }

  /** The attributes that create a file or directory with {@code permissions}, if POSIX. */
  static FileAttribute<?>[] permissions(FileSystem fileSystem, String permissions) {
    if (!fileSystem.supportedFileAttributeViews().contains("posix")) {
      return new FileAttribute<?>[0];
    }
    return new FileAttribute<?>[] {
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))
    };
  }

  private Path path(String sessionId) {
    return directory.resolve(SessionStore.requireValidId(sessionId) + SUFFIX);
  }

  private SessionFile file(String sessionId) {
    return new SessionFile(path(sessionId), fileAttributes);
  }

  /** This process's lock on the session's file, shared by every store on the same directory. */
  private Object lockOf(String sessionId) {
    return FILE_LOCKS[Math.floorMod(path(sessionId).hashCode(), FILE_LOCKS.length)];
  }

  private static Object[] newLocks(int count) {
    var locks = new Object[count];
    for (var i = 0; i < count; i++) {
      locks[i] = new Object();
    }
    return locks;
  }
}
