/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * A pinned Linux file descriptor: the entry it opened stays the entry it refers to, whatever
 * happens to the name afterwards. Children are opened, created and unlinked relative to it, and its
 * metadata and content are reached through the kernel-owned {@code /proc/self/fd} name, in which no
 * model-controlled component participates. {@link PinnedInput} and {@link PinnedOutput} are the
 * streams that hold a handle open for as long as they are.
 */
final class Handle implements AutoCloseable {
  private int fd;

  private Handle(int fd) {
    this.fd = fd;
  }

  static Handle filesystemRoot() throws IOException {
    return new Handle(
        LinuxSyscalls.openAt(
            LinuxSyscalls.AT_FDCWD, "/", LinuxSyscalls.PIN | LinuxSyscalls.O_DIRECTORY, 0));
  }

  Handle open(String name, int flags, int mode) throws IOException {
    return new Handle(LinuxSyscalls.openAt(fd, name, flags, mode));
  }

  void makeDirectory(String name, int mode) throws IOException {
    LinuxSyscalls.mkdirAt(fd, name, mode);
  }

  void unlink(String name) throws IOException {
    LinuxSyscalls.unlinkAt(fd, name);
  }

  Path procPath() throws IOException {
    if (fd < 0) {
      throw new IOException("descriptor is closed");
    }
    return LinuxSyscalls.DESCRIPTORS.resolve(Integer.toString(fd));
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
    LinuxSyscalls.close(closing);
  }

  /** A read stream that owns its handle and refuses to read past a byte limit. */
  static final class PinnedInput extends FilterInputStream {
    private final Handle entry;
    private long remaining;

    PinnedInput(InputStream input, Handle entry, long maxBytes) {
      super(input);
      this.entry = entry;
      this.remaining = maxBytes;
    }

    static InputStream limited(InputStream input, long maxBytes) {
      return new PinnedInput(input, null, maxBytes);
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

  /** A write stream that owns its handle. */
  static final class PinnedOutput extends FilterOutputStream {
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
