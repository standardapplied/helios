/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static com.standardapplied.helios.repl.protocol.ProcessTransport.MAX_CAPTURED_OUTPUT_CHARS;
import static com.standardapplied.helios.repl.protocol.ProcessTransport.MAX_FRAME_CHARS;

import com.standardapplied.helios.repl.protocol.CapturedOutput;
import com.standardapplied.helios.repl.protocol.ProcessTransport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The UTF-8 bytes a snippet prints during one execute, kept up to {@link
 * ProcessTransport#MAX_CAPTURED_OUTPUT_CHARS} characters and read as {@link CapturedOutput} reads
 * them. Output that JSON escapes heavily is cut earlier, at {@link #MAX_ESCAPED_CHARS}, because the
 * reply carries it escaped. A write never blocks or fails, so ordinary printing never reaches the
 * RPC frame cap, and a character is kept whole or not at all. Bytes that are not UTF-8, which a
 * snippet can write directly, are bounded too but counted approximately.
 */
final class CapturedStream extends OutputStream {

  /** One capture's share of a reply frame, which also holds its fields and a marker. */
  static final int MAX_ESCAPED_CHARS = (MAX_FRAME_CHARS - 4_096) / 3;

  private static final int MAX_UTF8_BYTES_PER_CHAR = 3;

  private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
  private long chars;
  private long escapedChars;

  @Override
  public synchronized void write(int value) {
    if ((value & 0xC0) != 0x80) {
      var units = (value & 0xF8) == 0xF0 ? 2 : 1;
      chars += units;
      escapedChars += escapedWidth(value & 0xFF, units);
    }
    if (chars <= MAX_CAPTURED_OUTPUT_CHARS
        && escapedChars <= MAX_ESCAPED_CHARS
        && kept.size() < MAX_CAPTURED_OUTPUT_CHARS * MAX_UTF8_BYTES_PER_CHAR) {
      kept.write(value);
    }
  }

  /** One lock across the buffer, so a thread still printing is never cut inside a character. */
  @Override
  public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
    super.write(bytes, offset, length);
  }

  /** How many chars the first byte of a character of {@code units} chars takes in a JSON string. */
  private static int escapedWidth(int firstByte, int units) {
    return switch (firstByte) {
      case '"', '\\', '\b', '\t', '\n', '\f', '\r' -> 2;
      default -> firstByte < 0x20 ? 6 : units;
    };
  }

  /** What {@code captures} kept and dropped, in order, cut as one output. */
  static CapturedOutput.Cut cut(CapturedStream... captures) {
    var output = new CapturedOutput();
    for (var capture : captures) {
      capture.appendTo(output);
    }
    return output.take();
  }

  private synchronized void appendTo(CapturedOutput output) {
    var text = kept.toString(StandardCharsets.UTF_8);
    output.append(text);
    output.discard(Math.max(0, chars - text.length()));
  }
}
