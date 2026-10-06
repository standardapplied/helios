/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import com.standardapplied.helios.repl.protocol.ProcessTransport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * What the sandbox subprocess printed since the last execute. A launched sandbox reads its
 * subprocess's stdout on a virtual thread of its own, joining lines with {@code \n}; RPC travels
 * over the socket and never through this stream. A sandbox wired directly to a {@link
 * ProcessTransport} drains the lines that transport set aside as not being RPC.
 */
final class StdoutCapture {

  private final Supplier<String> drain;
  private final Thread reader;

  private StdoutCapture(Supplier<String> drain, Thread reader) {
    this.drain = drain;
    this.reader = reader;
  }

  /** Start reading {@code process}'s stdout until the stream ends. */
  static StdoutCapture start(Process process) {
    var buffer = new StringBuilder();
    var reader =
        Thread.ofVirtual()
            .name("helios-sandbox-stdout-reader")
            .start(() -> readLines(process, buffer));
    return new StdoutCapture(() -> drainBuffer(buffer), reader);
  }

  /** The output {@code transport} set aside, for a sandbox with no reader of its own. */
  static StdoutCapture of(ProcessTransport transport) {
    return new StdoutCapture(transport::drainStdout, null);
  }

  /** The output captured since the last drain; the buffer is cleared for the next execute. */
  String drain() {
    return drain.get();
  }

  /** Stop the reader of a launch that failed. */
  void interrupt() {
    if (reader != null) {
      reader.interrupt();
    }
  }

  /**
   * Wait up to {@code grace} for the reader to see the end of the stream, which it does once the
   * subprocess is dead. A subprocess stuck in uninterruptible kernel sleep keeps the pipe open; the
   * wait then gives up and the reader thread leaks, one per stuck sandbox.
   */
  void awaitEnd(Duration grace) {
    if (reader == null) {
      return;
    }
    try {
      reader.join(grace);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void readLines(Process process, StringBuilder buffer) {
    try (var lines =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = lines.readLine()) != null) {
        synchronized (buffer) {
          if (!buffer.isEmpty()) {
            buffer.append('\n');
          }
          buffer.append(line);
        }
      }
    } catch (IOException ignored) {
    }
  }

  private static String drainBuffer(StringBuilder buffer) {
    synchronized (buffer) {
      var captured = buffer.toString();
      buffer.setLength(0);
      return captured;
    }
  }
}
