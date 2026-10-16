/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.core.test.Await;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * One HTTP/1.1 connection written byte for byte, for framing tests no HTTP client would produce.
 * Every read waits for the server through {@link Await}.
 */
final class RawConnection implements AutoCloseable {

  private static final byte[] END_OF_HEAD = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

  private final Socket socket;
  private final InputStream in;
  private final OutputStream out;

  private RawConnection(Socket socket) throws IOException {
    this.socket = socket;
    this.in = socket.getInputStream();
    this.out = socket.getOutputStream();
  }

  /** Connect to {@code port} on the loopback address. */
  static RawConnection open(int port) throws IOException {
    return new RawConnection(new Socket("127.0.0.1", port));
  }

  int localPort() {
    return socket.getLocalPort();
  }

  void write(String ascii) throws IOException {
    write(ascii.getBytes(StandardCharsets.US_ASCII));
  }

  void write(byte[] bytes) throws IOException {
    out.write(bytes);
    out.flush();
  }

  /** Read one response: its head, then exactly the body its {@code Content-Length} declares. */
  Response response() {
    return Await.value("a response to be read", CompletableFuture.supplyAsync(this::readResponse));
  }

  /** The next byte the server sends; {@code -1} once it has closed the connection. */
  int next() {
    return Await.value(
        "the server's next byte or close", CompletableFuture.supplyAsync(this::readByte));
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }

  private Response readResponse() {
    var head = new ByteArrayOutputStream();
    while (!endsWith(head.toByteArray(), END_OF_HEAD)) {
      var b = readByte();
      if (b < 0) {
        throw new IllegalStateException("connection closed inside a response head: " + head);
      }
      head.write(b);
    }
    var lines = head.toString(StandardCharsets.US_ASCII).split("\r\n");
    var headers = new HashMap<String, String>();
    for (var i = 1; i < lines.length; i++) {
      var colon = lines[i].indexOf(':');
      headers.put(
          lines[i].substring(0, colon).toLowerCase(Locale.ROOT),
          lines[i].substring(colon + 1).trim());
    }
    var length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
    try {
      var body = in.readNBytes(length);
      return new Response(lines[0], Map.copyOf(headers), new String(body, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private int readByte() {
    try {
      return in.read();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean endsWith(byte[] bytes, byte[] suffix) {
    if (bytes.length < suffix.length) {
      return false;
    }
    for (var i = 0; i < suffix.length; i++) {
      if (bytes[bytes.length - suffix.length + i] != suffix[i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * A response as read off the connection.
   *
   * @param statusLine e.g. {@code HTTP/1.1 202 Accepted}
   * @param headers header values by lower-case name
   * @param body the body, exactly as long as declared
   */
  record Response(String statusLine, Map<String, String> headers, String body) {

    boolean closesConnection() {
      return "close".equalsIgnoreCase(headers.get("connection"));
    }
  }
}
