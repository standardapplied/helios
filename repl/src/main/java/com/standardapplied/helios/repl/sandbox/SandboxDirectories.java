/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;

/**
 * The directories one sandbox owns: the private directory its RPC socket is bound in and, unless
 * the caller pinned a working directory, the ephemeral directory its subprocess runs in. Both are
 * created mode 0700 on POSIX filesystems; {@link #delete()} removes both and everything in them. On
 * a non-POSIX filesystem (Windows) the mode is left to {@link Files#createTempDirectory}, whose
 * ACLs Helios has not validated; deployers there must verify them before relying on cross-process
 * isolation.
 *
 * @param socketDirectory the private directory holding the RPC socket, or {@code null} when the
 *     sandbox has no socket (a transport wired directly in tests)
 * @param ephemeralWorkingDirectory the per-session scratch directory, or {@code null} when the
 *     caller pinned {@link JvmSandboxConfig#workingDirectory()} or the sandbox has no subprocess
 *     directories at all
 * @param workingDirectory the directory the subprocess runs in: the caller's, or the ephemeral one
 */
record SandboxDirectories(
    Path socketDirectory, Path ephemeralWorkingDirectory, Path workingDirectory) {

  /** A sandbox that owns no directories. */
  static final SandboxDirectories NONE = new SandboxDirectories(null, null, null);

  /**
   * Create the socket directory and, when {@code configuredWorkingDirectory} is {@code null}, an
   * ephemeral working directory. A caller-pinned directory is used verbatim and never deleted: its
   * lifecycle is the caller's. If the second directory cannot be created the first is deleted.
   */
  static SandboxDirectories create(Path configuredWorkingDirectory) throws IOException {
    var socketDirectory = createPrivate("helios-rpc-");
    if (configuredWorkingDirectory != null) {
      return new SandboxDirectories(socketDirectory, null, configuredWorkingDirectory);
    }
    try {
      var ephemeral = createPrivate("helios-sandbox-cwd-");
      return new SandboxDirectories(socketDirectory, ephemeral, ephemeral);
    } catch (IOException | RuntimeException e) {
      deleteSocketDirectory(socketDirectory);
      throw e;
    }
  }

  /** Where the RPC socket is bound. */
  Path socketPath() {
    return socketDirectory.resolve("rpc.sock");
  }

  /**
   * Delete the socket directory, then the ephemeral working directory; every failure is ignored.
   */
  void delete() {
    if (socketDirectory != null) {
      deleteSocketDirectory(socketDirectory);
    }
    if (ephemeralWorkingDirectory != null) {
      deleteRecursively(ephemeralWorkingDirectory);
    }
  }

  private static Path createPrivate(String prefix) throws IOException {
    var dir = Files.createTempDirectory(prefix);
    try {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException ignored) {
    }
    return dir;
  }

  private static void deleteSocketDirectory(Path dir) {
    try (var entries = Files.list(dir)) {
      entries.forEach(SandboxDirectories::deleteQuietly);
    } catch (IOException ignored) {
    }
    deleteQuietly(dir);
  }

  /**
   * Recursively delete the ephemeral working directory and every file the snippet wrote into it.
   * Symlinks are deleted as links (not followed) so a snippet that managed to {@code ln -s / leak}
   * from inside the sandbox cannot trick host cleanup into walking the root filesystem. Every
   * failure is swallowed — this runs from {@code close()} / the shutdown hook / the launch-failure
   * path, and re-throwing would mask the original outcome. A residual orphan dir under the system
   * temp area is the worst case; the OS's tmpwatch (or the deployer's housekeeping) will eventually
   * reap it.
   */
  private static void deleteRecursively(Path dir) {
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try {
      Files.walkFileTree(
          dir,
          EnumSet.noneOf(FileVisitOption.class),
          Integer.MAX_VALUE,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              deleteQuietly(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) {
              deleteQuietly(d);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException ignored) {
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
    }
  }
}
