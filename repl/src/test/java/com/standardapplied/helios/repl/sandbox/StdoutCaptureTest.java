/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.FeedableInputStream;
import com.standardapplied.helios.core.test.LineSink;
import com.standardapplied.helios.repl.protocol.ProcessTransport;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
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
  void theReaderRunsOnAVirtualThreadOfItsOwn() {
    var process = new ReadRecordingProcess(InputStream.nullInputStream());
    var capture = StdoutCapture.start(process);

    capture.awaitEnd(Await.HANG_GUARD);

    assertEquals("helios-sandbox-stdout-reader", process.reader.getName());
    assertTrue(process.reader.isVirtual());
  }

  @Test
  void aStreamThatFailsEndsTheCaptureWithWhatWasReadBeforeIt() {
    var failing =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("stream closed");
          }
        };
    var printed = new ByteArrayInputStream("partial\n".getBytes(StandardCharsets.UTF_8));
    var capture =
        StdoutCapture.start(new ReadRecordingProcess(new SequenceInputStream(printed, failing)));

    capture.awaitEnd(Await.HANG_GUARD);

    assertEquals("partial", capture.drain());
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

  /** A process whose stdout is {@code stdout}, recording which thread opened it. */
  private static final class ReadRecordingProcess extends Process {
    private final InputStream stdout;
    private volatile Thread reader;

    ReadRecordingProcess(InputStream stdout) {
      this.stdout = stdout;
    }

    @Override
    public InputStream getInputStream() {
      reader = Thread.currentThread();
      return stdout;
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {}
  }
}
