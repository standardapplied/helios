/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static com.standardapplied.helios.repl.protocol.ProcessTransport.MAX_CAPTURED_OUTPUT_CHARS;

import com.standardapplied.helios.repl.protocol.CapturedOutput;
import com.standardapplied.helios.repl.protocol.ProcessTransport;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The UTF-8 bytes a snippet prints during one execute, kept up to {@link
 * ProcessTransport#MAX_CAPTURED_OUTPUT_CHARS} characters and read as {@link CapturedOutput} reads
 * them. A write never blocks or fails, so ordinary printing never reaches the RPC frame cap, and a
 * character is kept whole or not at all. Bytes that are not UTF-8, which a snippet can write
 * directly, are bounded too but counted approximately.
 */
final class CapturedStream extends OutputStream {

  private static final int MAX_UTF8_BYTES_PER_CHAR = 3;

  private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
  private long chars;

  @Override
  public synchronized void write(int value) {
    if ((value & 0xC0) != 0x80) {
      chars += (value & 0xF8) == 0xF0 ? 2 : 1;
    }
    if (chars <= MAX_CAPTURED_OUTPUT_CHARS
        && kept.size() < MAX_CAPTURED_OUTPUT_CHARS * MAX_UTF8_BYTES_PER_CHAR) {
      kept.write(value);
    }
  }

  /** The text kept, followed by the truncation marker if anything was dropped. */
  synchronized String text() {
    var text = kept.toString(StandardCharsets.UTF_8);
    var output = new CapturedOutput();
    output.append(text);
    output.discard(Math.max(0, chars - text.length()));
    return output.drain();
  }
}
