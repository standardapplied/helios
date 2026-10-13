/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.ProviderException;
import java.io.IOException;
import java.io.Reader;

/**
 * The server-sent-events wire format of one response body: its lines, each ending with CR, LF or
 * CRLF, and the data of each event they frame. A line {@code data:value} or {@code data: value}
 * appends {@code value} to the event's data, several {@code data} lines joining with a newline; a
 * blank line ends the event; comment lines and every other field are ignored; an event the body
 * cuts off before its blank line is dropped. A line and an event's data are each bounded by {@link
 * #MAX_FRAME_CHARS}; past the bound reading fails with the provider's exception.
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
  private static final String DONE_MARKER = "[DONE]";
  private static final int BUFFER_CHARS = 8192;

  private final Reader reader;
  private final ProviderFailure failure;
  private final char[] buffer = new char[BUFFER_CHARS];
  private int position;
  private int limit;
  private boolean skipLineFeed;

  /** Frames the text {@code reader} decodes; an oversized frame fails with {@code failure}. */
  SseFrames(Reader reader, ProviderFailure failure) {
    this.reader = reader;
    this.failure = failure;
  }

  /**
   * The data of the next event read from {@code lines}, trimmed, skipping events with no data and
   * the {@code [DONE]} marker; {@code null} at the end of the body.
   *
   * @throws ProviderException if the event's data exceeds {@link #MAX_FRAME_CHARS}
   */
  String payload(Lines lines) throws IOException {
    StringBuilder data = null;
    for (var line = lines.next(); line != null; line = lines.next()) {
      if (!line.isEmpty()) {
        data = appendData(data, line);
        continue;
      }
      var payload = data == null ? "" : data.toString().trim();
      if (!payload.isEmpty() && !DONE_MARKER.equals(payload)) {
        return payload;
      }
      data = null;
    }
    return null;
  }

  /**
   * The next line read from the body, without its terminator, or {@code null} at the end of the
   * body, where a line without a terminator belongs to an event the body cut off.
   *
   * @throws ProviderException if the line exceeds {@link #MAX_FRAME_CHARS}
   */
  String line() throws IOException {
    var line = new StringBuilder();
    while (true) {
      if (position == limit && !fill()) {
        return null;
      }
      var c = buffer[position++];
      if (skipLineFeed && c == '\n') {
        skipLineFeed = false;
        continue;
      }
      skipLineFeed = c == '\r';
      if (c == '\n' || c == '\r') {
        return line.toString();
      }
      if (line.length() == MAX_FRAME_CHARS) {
        throw tooLarge("line");
      }
      line.append(c);
    }
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
