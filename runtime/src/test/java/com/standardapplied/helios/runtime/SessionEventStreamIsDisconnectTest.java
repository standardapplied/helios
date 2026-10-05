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
import java.net.SocketException;
import org.junit.jupiter.api.Test;

/** Unit tests for the package-private {@link SessionEventStream#isDisconnect(Throwable)} helper. */
final class SessionEventStreamIsDisconnectTest {

  @Test
  void brokenPipeSocketExceptionIsDisconnect() {
    assertTrue(SessionEventStream.isDisconnect(new SocketException("Broken pipe")));
  }

  @Test
  void connectionResetSocketExceptionIsDisconnect() {
    assertTrue(SessionEventStream.isDisconnect(new SocketException("Connection reset by peer")));
  }

  @Test
  void socketClosedSocketExceptionIsDisconnect() {
    assertTrue(SessionEventStream.isDisconnect(new SocketException("Socket closed")));
  }

  @Test
  void brokenPipeIoExceptionIsDisconnect() {
    assertTrue(SessionEventStream.isDisconnect(new IOException("Broken pipe writing response")));
  }

  @Test
  void connectionResetIoExceptionIsDisconnect() {
    assertTrue(SessionEventStream.isDisconnect(new IOException("Connection reset")));
  }

  @Test
  void socketClosedIoExceptionIsDisconnect() {
    assertTrue(SessionEventStream.isDisconnect(new IOException("Socket closed for stream")));
  }

  @Test
  void unrelatedRuntimeExceptionIsNotDisconnect() {
    assertFalse(SessionEventStream.isDisconnect(new RuntimeException("model boom")));
  }

  @Test
  void socketExceptionWithNullMessageIsNotDisconnect() {
    assertFalse(SessionEventStream.isDisconnect(new SocketException()));
  }

  @Test
  void socketExceptionWithUnrelatedMessageIsNotDisconnect() {
    assertFalse(SessionEventStream.isDisconnect(new SocketException("Permission denied")));
  }

  @Test
  void ioExceptionWithNullMessageIsNotDisconnect() {
    assertFalse(SessionEventStream.isDisconnect(new IOException()));
  }

  @Test
  void ioExceptionWithUnrelatedMessageIsNotDisconnect() {
    assertFalse(SessionEventStream.isDisconnect(new IOException("Disk full")));
  }

  @Test
  void nestedCauseIsExamined() {
    var inner = new SocketException("Broken pipe");
    var outer = new RuntimeException("wrapper", inner);
    assertTrue(SessionEventStream.isDisconnect(outer));
  }

  @Test
  void deeplyNestedNonDisconnectReturnsFalse() {
    var outer = new RuntimeException("a", new RuntimeException("b", new RuntimeException("c")));
    assertFalse(SessionEventStream.isDisconnect(outer));
  }

  @Test
  void nullCauseStopsTraversal() {
    var ex = new RuntimeException("no cause");
    assertFalse(SessionEventStream.isDisconnect(ex));
  }

  // ── Helidon typed disconnect signal (Phase C #17) ────────────────────────

  @Test
  void closeConnectionExceptionIsDisconnect() {
    assertTrue(SessionEventStream.isDisconnect(new CloseConnectionException("peer closed")));
  }

  @Test
  void serverConnectionExceptionIsDisconnect() {
    // ServerConnectionException extends CloseConnectionException.
    var ex = new ServerConnectionException("peer closed", new IOException("EOF"));
    assertTrue(SessionEventStream.isDisconnect(ex));
  }

  @Test
  void closeConnectionExceptionWrappedInRuntimeIsDisconnect() {
    var inner = new CloseConnectionException("peer closed");
    var outer = new RuntimeException("sink emit failed", inner);
    assertTrue(SessionEventStream.isDisconnect(outer));
  }
}
