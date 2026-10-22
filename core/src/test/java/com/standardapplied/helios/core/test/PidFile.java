/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A file a test's child process writes a pid to ({@code echo $$ > pid}), for a test that must find
 * or end a process it did not start itself, such as a backgrounded descendant. The file is empty or
 * absent until the pid is written.
 */
public final class PidFile {

  private PidFile() {}

  /** Waits until {@code file} holds a pid, then returns that process, which must still run. */
  public static ProcessHandle await(String description, Path file) {
    Await.until(description, () -> !read(file).isEmpty());
    return ProcessHandle.of(Long.parseLong(read(file))).orElseThrow();
  }

  /** Forcibly ends the process {@code file} records, if any still runs, and waits for its end. */
  public static void destroy(Path file) {
    var pid = read(file);
    if (!pid.isEmpty()) {
      ProcessHandle.of(Long.parseLong(pid))
          .ifPresent(
              handle -> {
                handle.destroyForcibly();
                Await.value("the recorded process to end", handle.onExit());
              });
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file).strip();
    } catch (IOException e) {
      return "";
    }
  }
}
