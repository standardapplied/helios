/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.repl.host.HostFunctionRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetBoundException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How the host binds the socket its sandbox connects to and accepts that one connection: every
 * failure propagates, and the listener and an accepted connection the host cannot set up are closed
 * on the way out; the wait for the connection runs on a virtual thread of its own.
 */
class SandboxRpcTest {

  private static final int ATTEMPTS = 64;

  @TempDir Path dir;

  @Test
  void aListenerThatCannotBindIsClosedAndTheFailurePropagates() throws Exception {
    var unbindable = dir.resolve("missing").resolve("rpc.sock");
    var openBefore = openDescriptors();

    for (var i = 0; i < ATTEMPTS; i++) {
      assertThrows(IOException.class, () -> SandboxRpc.listen(unbindable));
    }

    assertTrue(
        openDescriptors() - openBefore < ATTEMPTS / 2, "each failed bind must close its listener");
    try (var listener = SandboxRpc.listen(dir.resolve("rpc.sock"))) {
      assertTrue(listener.isOpen());
    }
  }

  @Test
  void aConnectionTheHostCannotSetUpIsClosedAndTheFailurePropagates() throws Exception {
    var socketPath = dir.resolve("rpc.sock");
    var undeletable = Files.createDirectory(dir.resolve("occupied"));
    Files.writeString(undeletable.resolve("entry"), "keeps the directory non-empty");
    try (var listener = SandboxRpc.listen(socketPath);
        var client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      client.connect(UnixDomainSocketAddress.of(socketPath));

      assertThrows(
          DirectoryNotEmptyException.class,
          () ->
              SandboxRpc.accept(
                  listener,
                  undeletable,
                  Await.HANG_GUARD,
                  new HostFunctionRegistry(),
                  Await.HANG_GUARD));

      var read =
          CompletableFuture.supplyAsync(() -> readOneByte(client), Thread.ofVirtual()::start);
      assertEquals(-1, Await.value("the client's read of the closed connection", read));
      assertFalse(listener.isOpen());
    }
  }

  @Test
  void anInterruptedWaitForTheConnectionKeepsTheInterruptAndClosesTheListener() throws Exception {
    var listener = SandboxRpc.listen(dir.resolve("rpc.sock"));
    Thread.currentThread().interrupt();

    var thrown =
        assertThrows(
            IOException.class, () -> SandboxRpc.acceptWithTimeout(listener, Await.HANG_GUARD));

    assertTrue(Thread.interrupted());
    assertEquals("Interrupted while waiting for subprocess RPC connect", thrown.getMessage());
    assertInstanceOf(InterruptedException.class, thrown.getCause());
    assertFalse(listener.isOpen());
  }

  @Test
  void anAcceptThatFailsWithAnIoExceptionRethrowsIt() throws Exception {
    var listener = SandboxRpc.listen(dir.resolve("rpc.sock"));
    listener.close();

    assertThrows(
        ClosedChannelException.class,
        () -> SandboxRpc.acceptWithTimeout(listener, Await.HANG_GUARD));
  }

  @Test
  void anAcceptThatFailsOtherwiseIsWrappedAndClosesTheListener() throws Exception {
    var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);

    var thrown =
        assertThrows(
            IOException.class, () -> SandboxRpc.acceptWithTimeout(listener, Await.HANG_GUARD));

    assertEquals("RPC accept failed", thrown.getMessage());
    assertInstanceOf(NotYetBoundException.class, thrown.getCause());
    assertFalse(listener.isOpen());
  }

  @Test
  void theConnectionIsAwaitedOnItsOwnVirtualThreadAndAListenerThatWillNotCloseIsLeft()
      throws Exception {
    var listener = new UncloseableListener();

    assertNull(SandboxRpc.acceptWithTimeout(listener, Await.HANG_GUARD));

    assertEquals("helios-sandbox-rpc-accept", listener.acceptingThread.getName());
    assertTrue(listener.acceptingThread.isVirtual());
    assertEquals(1, listener.closeAttempts.get());
  }

  @Test
  void aConnectionThatWillNotCloseIsLeft() {
    var socket = new UncloseableSocket();

    new SandboxRpc(null, null, socket).closeSocket();

    assertEquals(1, socket.closeAttempts.get());
  }

  private static long openDescriptors() throws IOException {
    try (var descriptors = Files.list(Path.of("/proc/self/fd"))) {
      return descriptors.count();
    }
  }

  private static int readOneByte(SocketChannel client) {
    try {
      return client.read(ByteBuffer.allocate(1));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A listener whose one accept yields nothing and which fails every attempt to close it. */
  private static final class UncloseableListener extends ServerSocketChannel {
    private final AtomicInteger closeAttempts = new AtomicInteger();
    private volatile Thread acceptingThread;

    UncloseableListener() {
      super(SelectorProvider.provider());
    }

    @Override
    public SocketChannel accept() {
      acceptingThread = Thread.currentThread();
      return null;
    }

    @Override
    protected void implCloseSelectableChannel() throws IOException {
      closeAttempts.incrementAndGet();
      throw new IOException("cannot close");
    }

    @Override
    protected void implConfigureBlocking(boolean block) {}

    @Override
    public ServerSocketChannel bind(SocketAddress local, int backlog) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> ServerSocketChannel setOption(SocketOption<T> name, T value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T getOption(SocketOption<T> name) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Set<SocketOption<?>> supportedOptions() {
      return Set.of();
    }

    @Override
    public ServerSocket socket() {
      throw new UnsupportedOperationException();
    }

    @Override
    public SocketAddress getLocalAddress() {
      return null;
    }
  }

  /** A connection that fails every attempt to close it and supports nothing else. */
  private static final class UncloseableSocket extends SocketChannel {
    private final AtomicInteger closeAttempts = new AtomicInteger();

    UncloseableSocket() {
      super(SelectorProvider.provider());
    }

    @Override
    protected void implCloseSelectableChannel() throws IOException {
      closeAttempts.incrementAndGet();
      throw new IOException("cannot close");
    }

    @Override
    protected void implConfigureBlocking(boolean block) {}

    @Override
    public SocketChannel bind(SocketAddress local) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> SocketChannel setOption(SocketOption<T> name, T value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T getOption(SocketOption<T> name) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Set<SocketOption<?>> supportedOptions() {
      return Set.of();
    }

    @Override
    public SocketChannel shutdownInput() {
      throw new UnsupportedOperationException();
    }

    @Override
    public SocketChannel shutdownOutput() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Socket socket() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isConnected() {
      return true;
    }

    @Override
    public boolean isConnectionPending() {
      return false;
    }

    @Override
    public boolean connect(SocketAddress remote) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean finishConnect() {
      return true;
    }

    @Override
    public SocketAddress getRemoteAddress() {
      return null;
    }

    @Override
    public int read(ByteBuffer dst) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int write(ByteBuffer src) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SocketAddress getLocalAddress() {
      return null;
    }
  }
}
