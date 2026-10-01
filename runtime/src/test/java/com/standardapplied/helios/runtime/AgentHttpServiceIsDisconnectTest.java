/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.helidon.webserver.CloseConnectionException;
import io.helidon.webserver.ServerConnectionException;
import java.io.IOException;
import java.net.SocketException;
import org.junit.jupiter.api.Test;

/** Unit tests for the package-private {@link AgentHttpService#isDisconnect(Throwable)} helper. */
final class AgentHttpServiceIsDisconnectTest {

  @Test
  void brokenPipeSocketExceptionIsDisconnect() {
    assertTrue(AgentHttpService.isDisconnect(new SocketException("Broken pipe")));
  }

  @Test
  void connectionResetSocketExceptionIsDisconnect() {
    assertTrue(AgentHttpService.isDisconnect(new SocketException("Connection reset by peer")));
  }

  @Test
  void socketClosedSocketExceptionIsDisconnect() {
    assertTrue(AgentHttpService.isDisconnect(new SocketException("Socket closed")));
  }

  @Test
  void brokenPipeIoExceptionIsDisconnect() {
    assertTrue(AgentHttpService.isDisconnect(new IOException("Broken pipe writing response")));
  }

  @Test
  void connectionResetIoExceptionIsDisconnect() {
    assertTrue(AgentHttpService.isDisconnect(new IOException("Connection reset")));
  }

  @Test
  void socketClosedIoExceptionIsDisconnect() {
    assertTrue(AgentHttpService.isDisconnect(new IOException("Socket closed for stream")));
  }

  @Test
  void unrelatedRuntimeExceptionIsNotDisconnect() {
    assertFalse(AgentHttpService.isDisconnect(new RuntimeException("model boom")));
  }

  @Test
  void socketExceptionWithNullMessageIsNotDisconnect() {
    assertFalse(AgentHttpService.isDisconnect(new SocketException()));
  }

  @Test
  void socketExceptionWithUnrelatedMessageIsNotDisconnect() {
    assertFalse(AgentHttpService.isDisconnect(new SocketException("Permission denied")));
  }

  @Test
  void ioExceptionWithNullMessageIsNotDisconnect() {
    assertFalse(AgentHttpService.isDisconnect(new IOException()));
  }

  @Test
  void ioExceptionWithUnrelatedMessageIsNotDisconnect() {
    assertFalse(AgentHttpService.isDisconnect(new IOException("Disk full")));
  }

  @Test
  void nestedCauseIsExamined() {
    var inner = new SocketException("Broken pipe");
    var outer = new RuntimeException("wrapper", inner);
    assertTrue(AgentHttpService.isDisconnect(outer));
  }

  @Test
  void deeplyNestedNonDisconnectReturnsFalse() {
    var outer = new RuntimeException("a", new RuntimeException("b", new RuntimeException("c")));
    assertFalse(AgentHttpService.isDisconnect(outer));
  }

  @Test
  void nullCauseStopsTraversal() {
    var ex = new RuntimeException("no cause");
    assertFalse(AgentHttpService.isDisconnect(ex));
  }

  // ── Helidon typed disconnect signal (Phase C #17) ────────────────────────

  @Test
  void closeConnectionExceptionIsDisconnect() {
    assertTrue(AgentHttpService.isDisconnect(new CloseConnectionException("peer closed")));
  }

  @Test
  void serverConnectionExceptionIsDisconnect() {
    // ServerConnectionException extends CloseConnectionException.
    var ex = new ServerConnectionException("peer closed", new IOException("EOF"));
    assertTrue(AgentHttpService.isDisconnect(ex));
  }

  @Test
  void closeConnectionExceptionWrappedInRuntimeIsDisconnect() {
    var inner = new CloseConnectionException("peer closed");
    var outer = new RuntimeException("sink emit failed", inner);
    assertTrue(AgentHttpService.isDisconnect(outer));
  }
}
