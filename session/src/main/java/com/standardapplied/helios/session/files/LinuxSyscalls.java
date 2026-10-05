/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.Set;

/**
 * The Linux descriptor-relative system calls strict workspace I/O is built on — {@code openat},
 * {@code mkdirat}, {@code unlinkat} and {@code close} through the foreign-function API — with the
 * flag values of the supported architectures and the mapping of {@code errno} to the {@link
 * java.nio.file} exception a caller expects. Owns the platform and native-access requirement, which
 * fails closed everywhere else.
 */
final class LinuxSyscalls {
  static final int AT_FDCWD = -100;
  static final int O_WRONLY = 1;
  static final int O_CREAT = 64;
  static final int O_EXCL = 128;
  private static final boolean AARCH64 = System.getProperty("os.arch").equals("aarch64");
  static final int O_DIRECTORY = AARCH64 ? 16_384 : 65_536;
  static final int O_NOFOLLOW = AARCH64 ? 32_768 : 131_072;
  static final int O_CLOEXEC = 524_288;
  private static final int O_PATH = 2_097_152;
  static final int PIN = O_PATH | O_NOFOLLOW | O_CLOEXEC;
  static final Path DESCRIPTORS = Path.of("/proc/self/fd");

  private LinuxSyscalls() {}

  static void requireSupport(Path path) {
    if (!System.getProperty("os.name").equals("Linux")
        || !Set.of("amd64", "x86_64", "aarch64").contains(System.getProperty("os.arch"))
        || path.getFileSystem() != FileSystems.getDefault()
        || !Files.isDirectory(DESCRIPTORS)) {
      throw new UnsupportedOperationException(
          "strict workspace I/O requires Linux x86-64/AArch64, the default filesystem and /proc/self/fd");
    }
    var module = LinuxSyscalls.class.getModule();
    if (!module.isNativeAccessEnabled()) {
      throw new UnsupportedOperationException(
          "strict workspace I/O requires --enable-native-access="
              + (module.isNamed() ? module.getName() : "ALL-UNNAMED"));
    }
  }

  static int openAt(int parent, String name, int flags, int mode) throws IOException {
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
      return result;
    }
  }

  static void mkdirAt(int parent, String name, int mode) throws IOException {
    callAt(Native.MKDIRAT, parent, name, mode, "mkdirat");
  }

  static void unlinkAt(int parent, String name) throws IOException {
    callAt(Native.UNLINKAT, parent, name, 0, "unlinkat");
  }

  static void close(int fd) throws IOException {
    try (var arena = Arena.ofConfined()) {
      var state = arena.allocate(Native.STATE);
      int result;
      try {
        result = (int) Native.CLOSE.invokeExact(state, fd);
      } catch (Throwable failure) {
        throw linkageFailure(failure);
      }
      if (result < 0) {
        throw error(state, "close", Integer.toString(fd));
      }
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
}
