/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.protocol;

import java.io.IOException;

/**
 * Transport layer for sending and receiving JSON-RPC 2.0 messages. Implementations provide the
 * physical channel (stdin/stdout, TCP socket, etc.).
 */
public interface RpcTransport extends AutoCloseable {

  /**
   * Send a message through the transport.
   *
   * @param message the message to send
   * @throws IOException if writing fails
   */
  void send(RpcMessage message) throws IOException;

  /**
   * Receive the next message from the transport. Blocks until a message is available.
   *
   * @return the received message, or {@code null} if the transport is closed
   * @throws IOException if reading fails
   */
  RpcMessage receive() throws IOException;

  /**
   * Whether the transport is still open for communication.
   *
   * @return {@code true} if the transport can send and receive messages
   */
  boolean isOpen();

  /** Close the transport and release resources. */
  @Override
  void close() throws IOException;
}
