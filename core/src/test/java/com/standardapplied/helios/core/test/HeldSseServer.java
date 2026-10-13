/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import java.io.BufferedInputStream;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A plain-socket HTTP/1.1 server that streams server-sent-event replies a test releases part by
 * part, and reports when the client closes the connection a reply is held on. The n-th request
 * receives the n-th {@link Reply}, written as a chunked {@code 200} response: one chunk per part,
 * each part after the first behind a gate the test opens with {@link Reply#release()}, and with
 * {@link Reply#holdingHeaders} the status line too. A reply's connection is read throughout, so a
 * client that closes it while a gate is down is seen at once ({@link Reply#awaitClientClosed()}).
 * Speaks only what the JDK {@code HttpClient} sends: a request line, headers and a {@code
 * Content-Length} body.
 */
public final class HeldSseServer implements AutoCloseable {

  /** One response the server streams, its parts released by the test. */
  public static final class Reply {

    private final boolean holdHeaders;
    private final List<String> parts;
    private final CountDownLatch requested = new CountDownLatch(1);
    private final CountDownLatch clientClosed = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);
    private int released;
    private boolean closed;

    private Reply(boolean holdHeaders, List<String> parts) {
      this.holdHeaders = holdHeaders;
      this.parts = List.copyOf(parts);
    }

    /** A reply whose headers and first part are sent at once, every later part on release. */
    public static Reply of(String... parts) {
      return new Reply(false, List.of(parts));
    }

    /** A reply whose headers wait for the first release, every later part for the next one. */
    public static Reply holdingHeaders(String... parts) {
      return new Reply(true, List.of(parts));
    }

    /** Opens the next gate. */
    public synchronized void release() {
      released++;
      notifyAll();
    }

    /** Waits until the request this reply answers has arrived. */
    public void awaitRequest() {
      Await.latch("the request", requested);
    }

    /** Waits until the client has closed the connection this reply is written on. */
    public void awaitClientClosed() {
      Await.latch("the client closing the connection", clientClosed);
    }

    /** Waits until every part has been written. */
    public void awaitFinished() {
      Await.latch("the whole reply written", finished);
    }

    private synchronized boolean awaitGate(int gate) throws InterruptedException {
      while (released < gate && !closed) {
        wait();
      }
      return !closed;
    }

    private synchronized void connectionClosed() {
      closed = true;
      clientClosed.countDown();
      notifyAll();
    }

    private void writeTo(OutputStream out) throws IOException, InterruptedException {
      var gate = 0;
      if (holdHeaders && !awaitGate(++gate)) {
        return;
      }
      out.write(
          ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      for (var i = 0; i < parts.size(); i++) {
        if (i > 0 && !awaitGate(++gate)) {
          return;
        }
        var bytes = parts.get(i).getBytes(StandardCharsets.UTF_8);
        out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
      }
      out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      out.flush();
      finished.countDown();
    }
  }

  private final ServerSocket serverSocket;
  private final List<Reply> replies;
  private final AtomicInteger next = new AtomicInteger();
  private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();

  private HeldSseServer(ServerSocket serverSocket, List<Reply> replies) {
    this.serverSocket = serverSocket;
    this.replies = replies;
  }

  /** Starts a server on the loopback address answering the n-th request with {@code replies[n]}. */
  public static HeldSseServer start(Reply... replies) {
    try {
      var socket = new ServerSocket();
      socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      var server = new HeldSseServer(socket, List.of(replies));
      Thread.ofVirtual().start(server::acceptLoop);
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code http://<address>:<port>}. */
  public URI uri() {
    return URI.create(
        "http://"
            + serverSocket.getInetAddress().getHostAddress()
            + ":"
            + serverSocket.getLocalPort());
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
        openSockets.add(socket);
        Thread.ofVirtual().start(() -> serve(socket));
      } catch (IOException closed) {
        return;
      }
    }
  }

  /**
   * Reads requests on its own thread and writes their replies on this one, so a close is seen while
   * a reply is held.
   */
  private void serve(Socket socket) {
    var requests = new LinkedBlockingQueue<Optional<Reply>>();
    var reader = Thread.ofVirtual().start(() -> readRequests(socket, requests));
    try (socket) {
      var out = socket.getOutputStream();
      for (var reply = requests.take(); reply.isPresent(); reply = requests.take()) {
        reply.get().writeTo(out);
      }
    } catch (IOException | InterruptedException ended) {
      openSockets.remove(socket);
    }
    reader.interrupt();
  }

  private void readRequests(Socket socket, LinkedBlockingQueue<Optional<Reply>> requests) {
    Reply current = null;
    try {
      var in = new BufferedInputStream(socket.getInputStream());
      while (readRequest(in)) {
        current = replies.get(next.getAndIncrement());
        current.requested.countDown();
        requests.add(Optional.of(current));
      }
    } catch (IOException disconnected) {
      openSockets.remove(socket);
    }
    if (current != null) {
      current.connectionClosed();
    }
    requests.add(Optional.empty());
  }

  private static boolean readRequest(InputStream in) throws IOException {
    if (StubHttpServer.readLine(in) == null) {
      return false;
    }
    var length = 0;
    for (var line = StubHttpServer.readLine(in);
        line != null && !line.isEmpty();
        line = StubHttpServer.readLine(in)) {
      var colon = line.indexOf(':');
      if (line.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("content-length")) {
        length = Integer.parseInt(line.substring(colon + 1).trim());
      }
    }
    in.readNBytes(length);
    return true;
  }
}
