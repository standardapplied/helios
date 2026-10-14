/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the plain-socket HTTP fixtures share: reading a request as the JDK {@code HttpClient} sends
 * it, a request line, headers and a {@code Content-Length} body, and rethrowing a recorded misuse
 * on the test's thread. A request the fixtures cannot read is an {@link AssertionError} naming why.
 */
final class PlainHttp {

  private PlainHttp() {}

  /** Reads the next request, or returns {@code null} once the client has closed the connection. */
  static StubHttpServer.Request readRequest(InputStream in) throws IOException {
    var requestLine = readLine(in);
    if (requestLine == null) {
      return null;
    }
    var headers = new TreeMap<String, String>();
    var length = 0;
    for (var line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
      var colon = line.indexOf(':');
      if (colon < 0) {
        throw new AssertionError("Request header without a colon: " + line);
      }
      var name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      var value = line.substring(colon + 1).trim();
      if (name.equals("transfer-encoding")) {
        throw new AssertionError(
            "Request body sent with Transfer-Encoding: "
                + value
                + "; only a Content-Length body is read");
      }
      if (name.equals("content-length")) {
        length = contentLength(value);
      }
      headers.put(name, value);
    }
    var body = new String(in.readNBytes(length), StandardCharsets.UTF_8);
    return new StubHttpServer.Request(requestLine, Map.copyOf(headers), body);
  }

  /** Throws a fresh {@link AssertionError} carrying {@code failure}, if one was recorded. */
  static void throwIfFailed(AssertionError failure) {
    if (failure != null) {
      throw new AssertionError(failure.getMessage(), failure);
    }
  }

  private static int contentLength(String value) {
    if (!value.matches("\\d{1,9}")) {
      throw new AssertionError("Unreadable Content-Length: " + value);
    }
    return Integer.parseInt(value);
  }

  private static String readLine(InputStream in) throws IOException {
    var line = new ByteArrayOutputStream();
    for (var b = in.read(); b != '\n'; b = in.read()) {
      if (b == -1) {
        return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
      }
      if (b != '\r') {
        line.write(b);
      }
    }
    return line.toString(StandardCharsets.ISO_8859_1);
  }
}
