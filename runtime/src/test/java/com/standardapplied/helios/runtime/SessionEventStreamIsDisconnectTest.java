/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.helidon.webserver.CloseConnectionException;
import io.helidon.webserver.ServerConnectionException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketException;
import org.junit.jupiter.api.Test;

/**
 * {@link SessionEventStream#isDisconnect(Throwable)} decides by Helidon's typed signal alone, found
 * anywhere in the cause chain; a socket failure's message decides nothing.
 */
final class SessionEventStreamIsDisconnectTest {

  @Test
  void closeConnectionExceptionIsDisconnect() {
    assertTrue(SessionEventStream.isDisconnect(new CloseConnectionException("peer closed")));
  }

  @Test
  void serverConnectionExceptionIsDisconnect() {
    var ex = new ServerConnectionException("peer closed", new IOException("EOF"));
    assertTrue(SessionEventStream.isDisconnect(ex));
  }

  @Test
  void closeConnectionExceptionWrappedInRuntimeIsDisconnect() {
    var inner = new CloseConnectionException("peer closed");
    assertTrue(SessionEventStream.isDisconnect(new RuntimeException("sink emit failed", inner)));
  }

  @Test
  void closeConnectionExceptionWrappedInUncheckedIoIsDisconnect() {
    var inner = new CloseConnectionException("peer closed");
    var outer = new UncheckedIOException("write failed", new IOException("wrapped", inner));
    assertTrue(SessionEventStream.isDisconnect(outer));
  }

  @Test
  void brokenPipeIoExceptionIsNotDisconnect() {
    assertFalse(SessionEventStream.isDisconnect(new IOException("Broken pipe")));
  }

  @Test
  void connectionResetSocketExceptionIsNotDisconnect() {
    assertFalse(SessionEventStream.isDisconnect(new SocketException("Connection reset")));
  }

  @Test
  void unrelatedRuntimeExceptionIsNotDisconnect() {
    assertFalse(SessionEventStream.isDisconnect(new RuntimeException("model boom")));
  }

  @Test
  void aCauseChainEndingInNullStops() {
    var outer = new RuntimeException("a", new RuntimeException("b", new RuntimeException("c")));
    assertFalse(SessionEventStream.isDisconnect(outer));
  }
}
