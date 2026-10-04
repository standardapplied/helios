/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A plain-socket HTTP/1.1 server that records every request it receives, before answering it with
 * the reply its handler computes, and keeps connections alive. Because a request is recorded before
 * its reply is written, a client call that has returned has had every request it sent recorded: a
 * test asserts what arrived without waiting. Speaks only what the JDK {@code HttpClient} sends: a
 * request line, headers and a {@code Content-Length} body.
 */
public final class StubHttpServer implements AutoCloseable {

  /** One received request; header names are lower-cased. */
  public record Request(String requestLine, Map<String, String> headers, String body) {}

  /** The reply to a request; {@code Content-Length} is added. */
  public record Reply(int status, Map<String, String> headers, String body) {}

  private final ServerSocket serverSocket;
  private final Function<Request, Reply> handler;
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final AtomicInteger connections = new AtomicInteger();
  private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();

  private StubHttpServer(ServerSocket serverSocket, Function<Request, Reply> handler) {
    this.serverSocket = serverSocket;
    this.handler = handler;
  }

  /**
   * Start a server on {@code address}.
   *
   * @param address the IPv4 address to listen on
   * @param port the port, or 0 for an ephemeral one
   * @param handler computes the reply to each request
   * @return the running server
   */
  public static StubHttpServer start(
      InetAddress address, int port, Function<Request, Reply> handler) {
    try {
      var socket = new ServerSocket();
      socket.bind(new InetSocketAddress(address, port));
      var server = new StubHttpServer(socket, handler);
      Thread.ofVirtual().start(server::acceptLoop);
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code http://<address>:<port>}. */
  public URI uri() {
    return URI.create("http://" + serverSocket.getInetAddress().getHostAddress() + ":" + port());
  }

  /** The port this server listens on. */
  public int port() {
    return serverSocket.getLocalPort();
  }

  /** Every request received so far, in arrival order. */
  public List<Request> requests() {
    return List.copyOf(requests);
  }

  /** How many connections this server has accepted. */
  public int connections() {
    return connections.get();
  }

  @Override
  public void close() throws IOException {
    serverSocket.close();
    for (var socket : openSockets) {
      socket.close();
    }
  }

  private void acceptLoop() {
    while (!serverSocket.isClosed()) {
      try {
        var socket = serverSocket.accept();
        connections.incrementAndGet();
        openSockets.add(socket);
        Thread.ofVirtual().start(() -> serve(socket));
      } catch (IOException closed) {
        return;
      }
    }
  }

  private void serve(Socket socket) {
    try (socket) {
      var in = new BufferedInputStream(socket.getInputStream());
      var out = socket.getOutputStream();
      for (var request = readRequest(in); request != null; request = readRequest(in)) {
        requests.add(request);
        write(out, handler.apply(request));
      }
    } catch (IOException disconnected) {
      openSockets.remove(socket);
    }
  }

  private static Request readRequest(InputStream in) throws IOException {
    var requestLine = readLine(in);
    if (requestLine == null) {
      return null;
    }
    var headers = new TreeMap<String, String>();
    for (var line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
      var colon = line.indexOf(':');
      headers.put(
          line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
          line.substring(colon + 1).trim());
    }
    var length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
    var body = new String(in.readNBytes(length), StandardCharsets.UTF_8);
    return new Request(requestLine, Map.copyOf(headers), body);
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

  private static void write(OutputStream out, Reply reply) throws IOException {
    var body = reply.body().getBytes(StandardCharsets.UTF_8);
    var head = new StringBuilder("HTTP/1.1 ").append(reply.status()).append(" Stub\r\n");
    reply
        .headers()
        .forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
    head.append("Content-Length: ").append(body.length).append("\r\n\r\n");
    out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    out.write(body);
    out.flush();
  }
}
