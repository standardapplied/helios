/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.FeedableInputStream;
import com.standardapplied.helios.core.test.LineSink;
import com.standardapplied.helios.repl.protocol.ProcessTransport;
import org.junit.jupiter.api.Test;

/**
 * What a sandbox reports as its subprocess's own output: the lines printed since the last drain,
 * joined with {@code \n}; and, for a sandbox wired straight to a transport, what that transport set
 * aside.
 */
class StdoutCaptureTest {

  @Test
  void linesPrintedSinceTheLastDrainAreJoinedWithNewlines() throws Exception {
    var process = new ProcessBuilder("printf", "first\\nsecond\\n").start();
    var capture = StdoutCapture.start(process);

    capture.awaitEnd(Await.HANG_GUARD);

    assertEquals("first\nsecond", capture.drain());
    assertEquals("", capture.drain());
  }

  @Test
  void anInterruptedWaitForTheEndOfTheStreamKeepsTheInterrupt() throws Exception {
    var process = new ProcessBuilder("sleep", "600").start();
    try {
      var capture = StdoutCapture.start(process);
      Thread.currentThread().interrupt();

      capture.awaitEnd(Await.HANG_GUARD);

      assertTrue(Thread.interrupted());
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  void aCaptureWithoutAReaderDrainsTheTransportAndHasNothingToStop() throws Exception {
    var fromProcess = new FeedableInputStream();
    var transport = new ProcessTransport(fromProcess, new LineSink());
    var capture = StdoutCapture.of(transport);
    fromProcess.feed("plain output\n");
    fromProcess.feed(ProcessTransport.RPC_PREFIX + "{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}\n");
    transport.receive();

    capture.interrupt();
    capture.awaitEnd(Await.HANG_GUARD);

    assertEquals("plain output", capture.drain());
  }
}
