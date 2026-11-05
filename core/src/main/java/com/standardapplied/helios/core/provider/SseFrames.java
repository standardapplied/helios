/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.ProviderException;
import java.io.IOException;
import java.io.Reader;
import java.util.Set;

/**
 * The server-sent-events wire format of one response body: its lines, each ending with CR, LF or
 * CRLF, and the data of each event they frame. One byte order mark at the start of the body is
 * dropped. A line {@code data:value} or {@code data: value} appends {@code value} to the event's
 * data, several {@code data} lines joining with a newline; a blank line ends the event its lines
 * began, and a blank line no line began is no event; comment lines and every other field add no
 * data; an event the body cuts off before its blank line is dropped. A line and an event's data are
 * each bounded by {@link #MAX_FRAME_CHARS}; past the bound reading fails with the provider's
 * exception.
 */
final class SseFrames {

  /** Reads one line at a time. */
  @FunctionalInterface
  interface Lines {

    /** The next line without its terminator, or {@code null} at the end of the body. */
    String next() throws IOException;
  }

  /** The most characters one line, or the data of one event, may hold. */
  static final int MAX_FRAME_CHARS = 16 * 1024 * 1024;

  private static final String DATA_FIELD = "data";
  private static final Set<String> FIELDS = Set.of(DATA_FIELD, "event", "id", "retry");
  private static final String DONE_MARKER = "[DONE]";
  private static final int BUFFER_CHARS = 8192;
  private static final char BYTE_ORDER_MARK = '\uFEFF';

  private final Reader reader;
  private final ProviderFailure failure;
  private final char[] buffer = new char[BUFFER_CHARS];
  private int position;
  private int limit;
  private boolean skipLineFeed;
  private boolean atBodyStart = true;
  private String lastLine;

  /** Frames the text {@code reader} decodes; an oversized frame fails with {@code failure}. */
  SseFrames(Reader reader, ProviderFailure failure) {
    this.reader = reader;
    this.failure = failure;
  }

  /**
   * The data of the next event read from {@code lines}, trimmed; empty for an event that carries
   * none, such as a comment-only heartbeat, blank data or the {@code [DONE]} marker; {@code null}
   * at the end of the body.
   *
   * @throws ProviderException if the event's data exceeds {@link #MAX_FRAME_CHARS}
   */
  String payload(Lines lines) throws IOException {
    StringBuilder data = null;
    var framed = false;
    for (var line = lines.next(); line != null; line = lines.next()) {
      if (!line.isEmpty()) {
        data = appendData(data, line);
        framed = true;
      } else if (framed) {
        var payload = data == null ? "" : data.toString().trim();
        return DONE_MARKER.equals(payload) ? "" : payload;
      }
    }
    return null;
  }

  /**
   * The last non-blank line read when it is not part of the server-sent-event format, else {@code
   * null}. A line is part of the format when it is a comment, starting with {@code :}, or its field
   * name, the text before its first {@code :} or the whole line, is {@code data}, {@code event},
   * {@code id} or {@code retry}.
   */
  String unframedLine() {
    return lastLine == null || isFramed(lastLine) ? null : lastLine;
  }

  /**
   * The next line read from the body, without its terminator, or {@code null} at the end of the
   * body; a non-empty last line the body ends without a terminator is still a line.
   *
   * @throws ProviderException if the line exceeds {@link #MAX_FRAME_CHARS}
   */
  String line() throws IOException {
    if (atBodyStart) {
      atBodyStart = false;
      skipByteOrderMark();
    }
    var line = new StringBuilder();
    while (true) {
      if (position == limit && !fill()) {
        return lastLineOf(line);
      }
      var c = buffer[position++];
      if (skipLineFeed && c == '\n') {
        skipLineFeed = false;
        continue;
      }
      skipLineFeed = c == '\r';
      if (c == '\n' || c == '\r') {
        return read(line);
      }
      if (line.length() == MAX_FRAME_CHARS) {
        throw tooLarge("line");
      }
      line.append(c);
    }
  }

  /** The body's unterminated last line, or {@code null} when it ended with a terminator. */
  private String lastLineOf(StringBuilder line) {
    return line.isEmpty() ? null : read(line);
  }

  private String read(StringBuilder line) {
    var text = line.toString();
    if (!text.isBlank()) {
      lastLine = text;
    }
    return text;
  }

  private static boolean isFramed(String line) {
    var colon = line.indexOf(':');
    return colon == 0 || FIELDS.contains(colon < 0 ? line : line.substring(0, colon));
  }

  private StringBuilder appendData(StringBuilder data, String line) {
    var value = dataValue(line);
    if (value == null) {
      return data;
    }
    var joined = data == null ? new StringBuilder() : data.append('\n');
    joined.append(value);
    if (joined.length() > MAX_FRAME_CHARS) {
      throw tooLarge("event");
    }
    return joined;
  }

  /** The value of a {@code data} field line, or {@code null} for any other line. */
  private static String dataValue(String line) {
    if (line.equals(DATA_FIELD)) {
      return "";
    }
    if (!line.startsWith(DATA_FIELD + ":")) {
      return null;
    }
    var value = line.substring(DATA_FIELD.length() + 1);
    return value.startsWith(" ") ? value.substring(1) : value;
  }

  private void skipByteOrderMark() throws IOException {
    if (fill() && buffer[0] == BYTE_ORDER_MARK) {
      position = 1;
    }
  }

  private boolean fill() throws IOException {
    position = 0;
    limit = Math.max(0, reader.read(buffer, 0, buffer.length));
    return limit > 0;
  }

  private ProviderException tooLarge(String frame) {
    return failure.create(
        "Stream " + frame + " exceeds " + MAX_FRAME_CHARS + " characters", 0, null);
  }
}
