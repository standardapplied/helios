/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import static com.standardapplied.helios.session.files.LinuxSyscalls.O_CLOEXEC;
import static com.standardapplied.helios.session.files.LinuxSyscalls.O_CREAT;
import static com.standardapplied.helios.session.files.LinuxSyscalls.O_DIRECTORY;
import static com.standardapplied.helios.session.files.LinuxSyscalls.O_EXCL;
import static com.standardapplied.helios.session.files.LinuxSyscalls.O_NOFOLLOW;
import static com.standardapplied.helios.session.files.LinuxSyscalls.O_WRONLY;
import static com.standardapplied.helios.session.files.LinuxSyscalls.PIN;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Objects;

/**
 * Linux descriptor-relative namespace operations. O_PATH pins an entry without opening it for
 * device/FIFO I/O. Only a pinned regular file is reopened through the kernel-owned /proc/self/fd
 * namespace; no model-controlled component participates in that reopen. This avoids native struct
 * layouts, private JDK APIs, and native read/write buffers. The system calls live in {@link
 * LinuxSyscalls}, the descriptors in {@link Handle} and directory enumeration in {@link
 * PinnedDirectoryStream}.
 */
final class LinuxFiles {

  private LinuxFiles() {}

  static BasicFileAttributes attributes(Path path) throws IOException {
    try (var entry = pin(path)) {
      return entry.attributes();
    }
  }

  static InputStream input(Path path, long maxBytes) throws IOException {
    if (maxBytes < 0) {
      throw new IllegalArgumentException("maxBytes must not be negative");
    }
    var entry = pin(path);
    try {
      var attrs = entry.regularFile();
      if (attrs.size() > maxBytes) {
        throw new IOException("file exceeds maximum size of " + maxBytes + " bytes: " + path);
      }
      return new Handle.PinnedInput(Files.newInputStream(entry.procPath()), entry, maxBytes);
    } catch (Throwable failure) {
      try (entry) {
        throw failure;
      }
    }
  }

  static OutputStream output(Path path, OpenOption... options) throws IOException {
    var mode = OutputMode.of(options);
    var entry = openEntry(path, mode.create(), mode.createNew());
    try {
      entry.regularFile();
      return new Handle.PinnedOutput(
          Files.newOutputStream(entry.procPath(), mode.options()), entry);
    } catch (Throwable failure) {
      try (entry) {
        throw failure;
      }
    }
  }

  private static Handle outputEntry(Handle parent, String name, boolean create, boolean createNew)
      throws IOException {
    if (createNew) {
      return parent.open(name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    }
    for (int attempt = 0; attempt < 8; attempt++) {
      try {
        return parent.open(name, PIN, 0);
      } catch (NoSuchFileException missing) {
        if (!create) {
          throw missing;
        }
        try {
          return parent.open(name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        } catch (FileAlreadyExistsException raced) {
          continue;
        }
      }
    }
    throw new IOException("file changed repeatedly during creation: " + name);
  }

  static void createDirectories(Path root, Path path) throws IOException {
    try (var ignored = descend(directory(root), root.relativize(path), true)) {}
  }

  static void deleteFile(Path path) throws IOException {
    if (path.getParent() == null) {
      throw new IOException("refusing to delete the filesystem root");
    }
    try (var parent = directory(path.getParent())) {
      parent.unlink(path.getFileName().toString());
    }
  }

  static Handle pin(Path path) throws IOException {
    return openEntry(path, false, false);
  }

  private static Handle openEntry(Path path, boolean create, boolean createNew) throws IOException {
    if (path.getParent() == null) {
      return directory(path);
    }
    Handle entry = null;
    try (var parent = directory(path.getParent())) {
      entry = outputEntry(parent, path.getFileName().toString(), create, createNew);
      return entry;
    } catch (Throwable failure) {
      try (var owned = entry) {
        throw failure;
      }
    }
  }

  static Handle directory(Path path) throws IOException {
    LinuxSyscalls.requireSupport(path);
    if (!path.isAbsolute() || !path.normalize().equals(path)) {
      throw new IllegalArgumentException("native path must be absolute and normalised: " + path);
    }
    return descend(Handle.filesystemRoot(), path, false);
  }

  private static Handle descend(Handle current, Path path, boolean create) throws IOException {
    try {
      for (var component : path) {
        var name = component.toString();
        if (name.isEmpty()) {
          continue;
        }
        Handle next;
        try {
          next = current.open(name, PIN | O_DIRECTORY, 0);
        } catch (NoSuchFileException missing) {
          if (!create) {
            throw missing;
          }
          try {
            current.makeDirectory(name, 0700);
          } catch (FileAlreadyExistsException raced) {
          }
          next = current.open(name, PIN | O_DIRECTORY, 0);
        }
        var previous = current;
        current = next;
        previous.close();
      }
      return current;
    } catch (Throwable failure) {
      try (var owned = current) {
        throw failure;
      }
    }
  }

  /**
   * Open options translated for a descriptor-relative open: whether a missing file is created
   * (exclusively or not), and the options for reopening the pinned file for writing.
   */
  private record OutputMode(boolean create, boolean createNew, OpenOption[] options) {

    static OutputMode of(OpenOption... options) {
      var requested = new HashSet<OpenOption>();
      for (var option : options) {
        Objects.requireNonNull(option, "open option must not be null");
        if (option != LinkOption.NOFOLLOW_LINKS && !(option instanceof StandardOpenOption)) {
          throw new UnsupportedOperationException("unsupported output option: " + option);
        }
        requested.add(option);
      }
      if (requested.contains(StandardOpenOption.READ)
          || (requested.contains(StandardOpenOption.APPEND)
              && requested.contains(StandardOpenOption.TRUNCATE_EXISTING))) {
        throw new IllegalArgumentException("invalid output options: " + requested);
      }
      if (requested.contains(StandardOpenOption.DELETE_ON_CLOSE)) {
        throw new UnsupportedOperationException("strict output does not support DELETE_ON_CLOSE");
      }
      if (options.length == 0) {
        requested.add(StandardOpenOption.CREATE);
        requested.add(StandardOpenOption.TRUNCATE_EXISTING);
      }
      boolean createNew = requested.remove(StandardOpenOption.CREATE_NEW);
      boolean create = requested.remove(StandardOpenOption.CREATE);
      requested.remove(LinkOption.NOFOLLOW_LINKS);
      requested.remove(StandardOpenOption.SPARSE);
      requested.add(StandardOpenOption.WRITE);
      return new OutputMode(create, createNew, requested.toArray(OpenOption[]::new));
    }
  }
}
