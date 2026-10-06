/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import com.standardapplied.helios.repl.ReplException;
import com.standardapplied.helios.repl.host.HostFunctionRegistry;
import com.standardapplied.helios.repl.protocol.ProcessTransport;
import com.standardapplied.helios.repl.protocol.RpcChannel;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The host's end of a sandbox's RPC connection: the one Unix-domain socket connection the
 * subprocess made, and the transport and channel over it. The host binds the socket with a backlog
 * of one, accepts exactly one connection, then closes the listener and deletes the socket file, so
 * no other process — including a snippet inside the subprocess — can connect a second time.
 * Subprocess stdout is never parsed as RPC, which closes the path by which a snippet writing an RPC
 * frame to its raw stdout could reach the host's dispatcher.
 *
 * @param transport the transport the channel reads and writes
 * @param channel the RPC channel
 * @param socket the accepted connection, or {@code null} when the transport is wired directly
 */
record SandboxRpc(ProcessTransport transport, RpcChannel channel, SocketChannel socket) {

  /** Bind the listener the subprocess connects to at {@code socketPath}. */
  static ServerSocketChannel listen(Path socketPath) throws IOException {
    var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    try {
      listener.bind(UnixDomainSocketAddress.of(socketPath), 1);
    } catch (IOException | RuntimeException e) {
      listener.close();
      throw e;
    }
    return listener;
  }

  /**
   * Accept the subprocess's connection within {@code startupTimeout}, delete the socket file and
   * open a channel over the connection whose calls time out after {@code callTimeout}.
   */
  static SandboxRpc accept(
      ServerSocketChannel listener,
      Path socketPath,
      Duration startupTimeout,
      HostFunctionRegistry registry,
      Duration callTimeout)
      throws IOException {
    var socket = acceptWithTimeout(listener, startupTimeout);
    try {
      Files.deleteIfExists(socketPath);
      var transport =
          new ProcessTransport(Channels.newInputStream(socket), Channels.newOutputStream(socket));
      return new SandboxRpc(transport, new RpcChannel(transport, registry, callTimeout), socket);
    } catch (IOException | RuntimeException e) {
      socket.close();
      throw e;
    }
  }

  /**
   * Accept exactly one inbound connection on {@code listener} or throw on timeout. Uses a virtual
   * thread so the calling thread retains its interrupt-status semantics and the timeout actually
   * fires (blocking {@code ServerSocketChannel#accept} has no built-in timeout in blocking mode).
   *
   * <p>Takes ownership of {@code listener}: closes it on every exit path (success or any failure
   * mode) before returning or throwing.
   */
  static SocketChannel acceptWithTimeout(ServerSocketChannel listener, Duration timeout)
      throws IOException {
    var accept =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return listener.accept();
              } catch (IOException e) {
                throw new CompletionException(e);
              }
            },
            r -> Thread.ofVirtual().name("helios-sandbox-rpc-accept").start(r));
    try {
      return accept.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new IOException(
          "Subprocess did not connect to the RPC socket within "
              + timeout
              + "; the launch"
              + " probably failed — check stderr for the cause",
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for subprocess RPC connect", e);
    } catch (ExecutionException e) {
      var cause = e.getCause();
      if (cause instanceof IOException io) {
        throw io;
      }
      throw new IOException("RPC accept failed", cause);
    } finally {
      try {
        listener.close();
      } catch (IOException ignored) {
      }
    }
  }

  /**
   * Install the JShell wrappers synthesized for the registry's custom host functions; a blank
   * {@code snippet} installs nothing.
   *
   * @throws ReplException if the call fails or the sandbox rejects the wrappers
   */
  void installPrelude(String snippet) {
    if (snippet.isBlank()) {
      return;
    }
    Object response;
    try {
      response = channel.call("installPrelude", Map.of("snippet", snippet));
    } catch (RpcChannel.RpcException e) {
      throw new ReplException(
          "Failed to install custom host-function wrappers in sandbox: " + e.getMessage(), e);
    }
    if (response instanceof Map<?, ?> m && Boolean.FALSE.equals(m.get("success"))) {
      var errors = m.get("errors") instanceof List<?> es ? es : List.of();
      throw new ReplException("Sandbox rejected custom host-function wrappers: " + errors);
    }
  }

  /** Close the accepted connection, if there is one; a failure to close is ignored. */
  void closeSocket() {
    if (socket == null) {
      return;
    }
    try {
      socket.close();
    } catch (IOException ignored) {
    }
  }
}
