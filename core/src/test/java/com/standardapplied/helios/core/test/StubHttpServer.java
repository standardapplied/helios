/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * A plain-socket HTTP/1.1 server that records every request it receives, before answering it with
 * the reply its handler computes, and keeps connections alive. Because a request is recorded before
 * its reply is written, a client call that has returned has had every request it sent recorded: a
 * test asserts what arrived without waiting. Speaks only what the JDK {@code HttpClient} sends: a
 * request line, headers and a {@code Content-Length} body. A request it cannot read, or a handler
 * that throws or returns no reply, fails the server: it drops every connection, and {@link
 * #requests()}, {@link #connections()} and {@link #close()} throw an {@link AssertionError} naming
 * the misuse.
 */
public final class StubHttpServer implements AutoCloseable {

  /** One received request; header names are lower-cased. */
  public record Request(String requestLine, Map<String, String> headers, String body) {}

  /** The reply to a request; {@code Content-Length} is added. */
  public record Reply(int status, Map<String, String> headers, String body) {

    /** Copies {@code headers}; neither it nor {@code body} may be null. */
    public Reply {
      headers = Map.copyOf(headers);
      Objects.requireNonNull(body, "body");
    }
  }

  private final ServerSocket serverSocket;
  private final Function<Request, Reply> handler;
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final AtomicInteger connections = new AtomicInteger();
  private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
  private final AtomicReference<AssertionError> failure = new AtomicReference<>();

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
    PlainHttp.throwIfFailed(failure.get());
    return List.copyOf(requests);
  }

  /** How many connections this server has accepted. */
  public int connections() {
    PlainHttp.throwIfFailed(failure.get());
    return connections.get();
  }

  @Override
  public void close() throws IOException {
    shutDown();
    PlainHttp.throwIfFailed(failure.get());
  }

  private void shutDown() throws IOException {
    serverSocket.close();
    for (var socket : openSockets) {
      socket.close();
    }
  }

  private void fail(AssertionError error) {
    if (!failure.compareAndSet(null, error)) {
      return;
    }
    try {
      shutDown();
    } catch (IOException e) {
      error.addSuppressed(e);
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
      answer(socket);
    } catch (IOException disconnected) {
      openSockets.remove(socket);
    }
  }

  /** Records a misuse before {@link #serve} closes the socket, so a dropped client finds it. */
  private void answer(Socket socket) throws IOException {
    var in = new BufferedInputStream(socket.getInputStream());
    var out = socket.getOutputStream();
    try {
      for (var request = PlainHttp.readRequest(in);
          request != null;
          request = PlainHttp.readRequest(in)) {
        requests.add(request);
        write(out, reply(request));
      }
    } catch (AssertionError misuse) {
      fail(misuse);
    }
  }

  private Reply reply(Request request) {
    Reply reply;
    try {
      reply = handler.apply(request);
    } catch (RuntimeException | AssertionError e) {
      throw new AssertionError("The handler failed on " + request.requestLine(), e);
    }
    if (reply == null) {
      throw new AssertionError("The handler returned no reply to " + request.requestLine());
    }
    return reply;
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
