/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import com.standardapplied.helios.repl.protocol.CapturedOutput;
import com.standardapplied.helios.repl.protocol.CapturedOutput.Cut;
import com.standardapplied.helios.repl.protocol.ProcessTransport;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * What the sandbox subprocess printed since the last execute. A launched sandbox reads its
 * subprocess's stdout in chunks on a virtual thread of its own, so output with no newline is
 * bounded by the {@link ProcessTransport#MAX_CAPTURED_OUTPUT_CHARS} cap of {@link CapturedOutput}
 * too, and the stream is read to its end so the subprocess never blocks on a full pipe; RPC travels
 * over the socket and never through this stream. A sandbox wired directly to a {@link
 * ProcessTransport} drains the lines that transport set aside as not being RPC.
 */
final class StdoutCapture {

  private static final int CHUNK_CHARS = 8192;

  private final Supplier<Cut> take;
  private final Thread reader;

  private StdoutCapture(Supplier<Cut> take, Thread reader) {
    this.take = take;
    this.reader = reader;
  }

  /** Start reading {@code process}'s stdout until the stream ends. */
  static StdoutCapture start(Process process) {
    var buffer = new CapturedOutput();
    var reader =
        Thread.ofVirtual()
            .name("helios-sandbox-stdout-reader")
            .start(() -> readChunks(process, buffer));
    return new StdoutCapture(() -> withoutFinalNewline(buffer.take()), reader);
  }

  /** The output {@code transport} set aside, for a sandbox with no reader of its own. */
  static StdoutCapture of(ProcessTransport transport) {
    return new StdoutCapture(transport::takeStdout, null);
  }

  /** The output captured since the last drain; the buffer is cleared for the next execute. */
  String drain() {
    return take().text();
  }

  /** As {@link #drain()}, for a caller that adds output of its own to what was captured. */
  Cut take() {
    return take.get();
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

  private static void readChunks(Process process, CapturedOutput buffer) {
    try (var output = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
      var chunk = new char[CHUNK_CHARS];
      int count;
      while ((count = output.read(chunk)) != -1) {
        buffer.append(new String(chunk, 0, count));
      }
    } catch (IOException ignored) {
    }
  }

  private static Cut withoutFinalNewline(Cut cut) {
    var kept = cut.kept();
    return kept.endsWith("\n")
        ? new Cut(kept.substring(0, kept.length() - 1), cut.discarded())
        : cut;
  }
}
