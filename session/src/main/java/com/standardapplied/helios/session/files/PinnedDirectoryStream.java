/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Enumeration of a pinned directory. The entries are read through the directory's descriptor, so
 * replacing the directory's name after it was pinned cannot redirect the listing, and each entry is
 * reported under the logical workspace path the caller asked for, never the descriptor path.
 */
final class PinnedDirectoryStream implements DirectoryStream<Path> {
  private final Path path;
  private final Handle directory;
  private final DirectoryStream<Path> entries;

  private PinnedDirectoryStream(Path path, Handle directory, DirectoryStream<Path> entries) {
    this.path = path;
    this.directory = directory;
    this.entries = entries;
  }

  static DirectoryStream<Path> open(Path path) throws IOException {
    var directory = LinuxFiles.directory(path);
    try {
      return new PinnedDirectoryStream(
          path, directory, Files.newDirectoryStream(directory.procPath()));
    } catch (Throwable failure) {
      try (directory) {
        throw failure;
      }
    }
  }

  @Override
  public Iterator<Path> iterator() {
    var iterator = entries.iterator();
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return iterator.hasNext();
      }

      @Override
      public Path next() {
        return path.resolve(iterator.next().getFileName());
      }
    };
  }

  @Override
  public void close() throws IOException {
    try (directory) {
      entries.close();
    }
  }
}
