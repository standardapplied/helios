/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.protocol;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;

/**
 * Reads {@code \n}-terminated lines, holding at most {@code maxChars} characters of one, so a peer
 * that never ends a line cannot grow the reader's memory.
 */
final class BoundedLineReader implements Closeable {

  private final Reader reader;
  private final int maxChars;

  BoundedLineReader(Reader reader, int maxChars) {
    this.reader = reader;
    this.maxChars = maxChars;
  }

  /**
   * Returns the next line without its terminator, or {@code null} at the end of the stream.
   *
   * @throws IOException if reading fails, or the line is longer than {@code maxChars}
   */
  String readLine() throws IOException {
    var line = new StringBuilder();
    for (var next = reader.read(); next != -1; next = reader.read()) {
      if (next == '\n') {
        return line.toString();
      }
      if (line.length() == maxChars) {
        throw new IOException("Line exceeds the limit of " + maxChars + " chars");
      }
      line.append((char) next);
    }
    return line.isEmpty() ? null : line.toString();
  }

  @Override
  public void close() throws IOException {
    reader.close();
  }
}
