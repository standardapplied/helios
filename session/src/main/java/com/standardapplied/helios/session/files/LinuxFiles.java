/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * Linux descriptor-relative namespace operations. O_PATH pins an entry without opening it for
 * device/FIFO I/O. Only a pinned regular file is reopened through the kernel-owned /proc/self/fd
 * namespace; no model-controlled component participates in that reopen. This avoids native struct
 * layouts, private JDK APIs, and native read/write buffers.
 */
final class LinuxFiles {
  private static final int AT_FDCWD = -100;
  private static final int O_WRONLY = 1;
  private static final int O_CREAT = 64;
  private static final int O_EXCL = 128;
  private static final boolean AARCH64 = System.getProperty("os.arch").equals("aarch64");
  private static final int O_DIRECTORY = AARCH64 ? 16_384 : 65_536;
  private static final int O_NOFOLLOW = AARCH64 ? 32_768 : 131_072;
  private static final int O_CLOEXEC = 524_288;
  private static final int O_PATH = 2_097_152;
  private static final int PIN = O_PATH | O_NOFOLLOW | O_CLOEXEC;
  private static final Path DESCRIPTORS = Path.of("/proc/self/fd");

  private LinuxFiles() {}

  static void requireSupport(Path path) {
    if (!System.getProperty("os.name").equals("Linux")
        || !Set.of("amd64", "x86_64", "aarch64").contains(System.getProperty("os.arch"))
        || path.getFileSystem() != FileSystems.getDefault()
        || !Files.isDirectory(DESCRIPTORS)) {
      throw new UnsupportedOperationException(
          "strict workspace I/O requires Linux x86-64/AArch64, the default filesystem and /proc/self/fd");
    }
    var module = LinuxFiles.class.getModule();
    if (!module.isNativeAccessEnabled()) {
      throw new UnsupportedOperationException(
          "strict workspace I/O requires --enable-native-access="
              + (module.isNamed() ? module.getName() : "ALL-UNNAMED"));
    }
  }

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
      return new PinnedInput(Files.newInputStream(entry.procPath()), entry, maxBytes);
    } catch (Throwable failure) {
      try (entry) {
        throw failure;
      }
    }
  }

  static InputStream limit(InputStream input, long maxBytes) {
    return new PinnedInput(input, null, maxBytes);
  }

  static OutputStream output(Path path, OpenOption... options) throws IOException {
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
    var entry = openEntry(path, create, createNew);
    try {
      entry.regularFile();
      return new PinnedOutput(
          Files.newOutputStream(entry.procPath(), requested.toArray(OpenOption[]::new)), entry);
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
      callAt(Native.UNLINKAT, parent.fd, path.getFileName().toString(), 0, "unlinkat");
    }
  }

  static DirectoryStream<Path> entries(Path path) throws IOException {
    var directory = directory(path);
    try {
      var entries = Files.newDirectoryStream(directory.procPath());
      return new DirectoryStream<>() {
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
      };
    } catch (Throwable failure) {
      try (directory) {
        throw failure;
      }
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
    requireSupport(path);
    if (!path.isAbsolute() || !path.normalize().equals(path)) {
      throw new IllegalArgumentException("native path must be absolute and normalised: " + path);
    }
    return descend(openAt(AT_FDCWD, "/", PIN | O_DIRECTORY, 0), path, false);
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
            callAt(Native.MKDIRAT, current.fd, name, 0700, "mkdirat");
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

  static final class Handle implements AutoCloseable {
    private int fd;

    private Handle(int fd) {
      this.fd = fd;
    }

    Handle open(String name, int flags, int mode) throws IOException {
      return openAt(fd, name, flags, mode);
    }

    Path procPath() throws IOException {
      if (fd < 0) {
        throw new IOException("descriptor is closed");
      }
      return DESCRIPTORS.resolve(Integer.toString(fd));
    }

    BasicFileAttributes attributes() throws IOException {
      return Files.readAttributes(procPath(), BasicFileAttributes.class);
    }

    BasicFileAttributes regularFile() throws IOException {
      var attrs = attributes();
      if (!attrs.isRegularFile()) {
        throw new IOException("entry is not a regular file");
      }
      return attrs;
    }

    @Override
    public synchronized void close() throws IOException {
      if (fd < 0) {
        return;
      }
      int closing = fd;
      fd = -1;
      try (var arena = Arena.ofConfined()) {
        var state = arena.allocate(Native.STATE);
        int result;
        try {
          result = (int) Native.CLOSE.invokeExact(state, closing);
        } catch (Throwable failure) {
          throw linkageFailure(failure);
        }
        if (result < 0) {
          throw error(state, "close", Integer.toString(closing));
        }
      }
    }
  }

  private static Handle openAt(int parent, String name, int flags, int mode) throws IOException {
    try (var arena = Arena.ofConfined()) {
      var state = arena.allocate(Native.STATE);
      var path = arena.allocateFrom(name);
      int result;
      try {
        result = (int) Native.OPENAT.invokeExact(state, parent, path, flags, mode);
      } catch (Throwable failure) {
        throw linkageFailure(failure);
      }
      if (result < 0) {
        throw error(state, "openat", name);
      }
      return new Handle(result);
    }
  }

  private static void callAt(
      MethodHandle call, int parent, String name, int flags, String operation) throws IOException {
    try (var arena = Arena.ofConfined()) {
      var state = arena.allocate(Native.STATE);
      var path = arena.allocateFrom(name);
      int result;
      try {
        result = (int) call.invokeExact(state, parent, path, flags);
      } catch (Throwable failure) {
        throw linkageFailure(failure);
      }
      if (result < 0) {
        throw error(state, operation, name);
      }
    }
  }

  private static IOException error(MemorySegment state, String operation, String path) {
    int errno = state.get(JAVA_INT, Native.ERRNO_OFFSET);
    return switch (errno) {
      case 2 -> new NoSuchFileException(path);
      case 13 -> new AccessDeniedException(path);
      case 17 -> new FileAlreadyExistsException(path);
      case 20 -> new NotDirectoryException(path);
      default -> new FileSystemException(path, null, operation + " failed (errno " + errno + ")");
    };
  }

  private static AssertionError linkageFailure(Throwable failure) {
    if (failure instanceof Error error) {
      throw error;
    }
    if (failure instanceof RuntimeException exception) {
      throw exception;
    }
    return new AssertionError("unexpected native linkage failure", failure);
  }

  private static final class Native {
    static final Linker LINKER = Linker.nativeLinker();
    static final MemoryLayout STATE = Linker.Option.captureStateLayout();
    static final long ERRNO_OFFSET =
        STATE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
    static final MethodHandle OPENAT =
        LINKER.downcallHandle(
            LINKER.defaultLookup().find("openat").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
            Linker.Option.captureCallState("errno"),
            Linker.Option.firstVariadicArg(3));
    static final MethodHandle MKDIRAT = at("mkdirat");
    static final MethodHandle UNLINKAT = at("unlinkat");
    static final MethodHandle CLOSE =
        LINKER.downcallHandle(
            LINKER.defaultLookup().find("close").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT),
            Linker.Option.captureCallState("errno"));

    private static MethodHandle at(String name) {
      return LINKER.downcallHandle(
          LINKER.defaultLookup().find(name).orElseThrow(),
          FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT),
          Linker.Option.captureCallState("errno"));
    }
  }

  private static final class PinnedInput extends FilterInputStream {
    private final Handle entry;
    private long remaining;

    PinnedInput(InputStream input, Handle entry, long maxBytes) {
      super(input);
      this.entry = entry;
      this.remaining = maxBytes;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0) {
        if (remaining == 0) {
          throw new IOException("file grew beyond the maximum read size");
        }
        remaining--;
      }
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, buffer.length);
      if (length == 0) {
        return 0;
      }
      if (remaining == 0) {
        return read() < 0 ? -1 : 0;
      }
      int read = in.read(buffer, offset, (int) Math.min(length, remaining));
      if (read > 0) {
        remaining -= read;
      }
      return read;
    }

    @Override
    public long skip(long count) throws IOException {
      long skipped = in.skip(Math.min(Math.max(count, 0), remaining));
      remaining -= skipped;
      return skipped;
    }

    @Override
    public void close() throws IOException {
      try (entry) {
        super.close();
      }
    }
  }

  private static final class PinnedOutput extends FilterOutputStream {
    private final Handle entry;

    PinnedOutput(OutputStream output, Handle entry) {
      super(output);
      this.entry = entry;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      out.write(bytes, offset, length);
    }

    @Override
    public void close() throws IOException {
      try (entry) {
        super.close();
      }
    }
  }
}
