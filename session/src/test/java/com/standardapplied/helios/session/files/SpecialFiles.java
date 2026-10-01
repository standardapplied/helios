/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import java.io.IOException;
import java.nio.file.Path;

/** Test helper creating special files the JDK cannot create directly. */
public final class SpecialFiles {

  private SpecialFiles() {}

  /**
   * Create a FIFO at {@code path} via {@code mkfifo}.
   *
   * @return {@code true} if the FIFO now exists; {@code false} if the platform has no mkfifo
   */
  public static boolean mkfifo(Path path) {
    try {
      var process = new ProcessBuilder("mkfifo", path.toString()).inheritIO().start();
      return process.waitFor() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
