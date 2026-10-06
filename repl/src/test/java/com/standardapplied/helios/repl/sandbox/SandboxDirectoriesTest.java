/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;

/**
 * Where a sandbox's directories live and what deleting them does: the socket sits in a {@code
 * helios-rpc-} directory and the subprocess runs in a {@code helios-sandbox-cwd-} directory, both
 * in the system temporary directory; deletion removes what it can and ignores what it cannot.
 */
class SandboxDirectoriesTest {

  @Test
  void theSocketAndTheWorkingDirectoryLiveInNamedTemporaryDirectories() throws IOException {
    var directories = SandboxDirectories.create(null);
    try {
      var temporary = Path.of(System.getProperty("java.io.tmpdir"));
      var socketDirectory = directories.socketDirectory();
      var workingDirectory = directories.ephemeralWorkingDirectory();

      assertEquals(socketDirectory.resolve("rpc.sock"), directories.socketPath());
      assertEquals(temporary, socketDirectory.getParent());
      assertTrue(socketDirectory.getFileName().toString().startsWith("helios-rpc-"));
      assertEquals(temporary, workingDirectory.getParent());
      assertTrue(workingDirectory.getFileName().toString().startsWith("helios-sandbox-cwd-"));
      assertEquals(workingDirectory, directories.workingDirectory());
    } finally {
      directories.delete();
    }
  }

  @Test
  void deletionRemovesWhatItCanAndIgnoresWhatItCannot() throws IOException {
    var directories = SandboxDirectories.create(null);
    var scratch = Files.writeString(directories.workingDirectory().resolve("scratch.txt"), "x");
    var locked = Files.createDirectory(directories.workingDirectory().resolve("locked"));
    var lockedFile = Files.writeString(locked.resolve("kept.txt"), "x");
    var nested = Files.createDirectory(directories.socketDirectory().resolve("nested"));
    var nestedFile = Files.writeString(nested.resolve("kept.txt"), "x");
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
    try {
      assumeFalse(Files.isReadable(locked), "this user can read a directory with mode 000");

      directories.delete();

      assertFalse(Files.exists(scratch));
      assertTrue(Files.isDirectory(locked));
      assertTrue(Files.exists(nestedFile));
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
      Files.deleteIfExists(lockedFile);
      Files.deleteIfExists(nestedFile);
      directories.delete();
    }
    assertFalse(Files.exists(directories.socketDirectory()));
    assertFalse(Files.exists(directories.workingDirectory()));
  }
}
