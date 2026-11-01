/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.atomic.AtomicReference;
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
  void takeLeavesTheFinalNewlineOutAndKeepsTheCount() {
    var printed = "x".repeat(ProcessTransport.MAX_CAPTURED_OUTPUT_CHARS - 1) + "\nab";
    var stdout = new ByteArrayInputStream(printed.getBytes(StandardCharsets.UTF_8));
    var capture = StdoutCapture.start(new ReadRecordingProcess(stdout));

    capture.awaitEnd(Await.HANG_GUARD);

    var cut = capture.take();
    assertEquals(printed.substring(0, ProcessTransport.MAX_CAPTURED_OUTPUT_CHARS - 1), cut.kept());
    assertEquals(2, cut.discarded());
  }

  @Test
  void outputWithNoNewlineIsCutAtTheCapAndTheStreamIsReadToItsEnd() {
    var printed = "x".repeat(3_000_000);
    var stdout = new ByteArrayInputStream(printed.getBytes(StandardCharsets.UTF_8));
    var process = new ReadRecordingProcess(stdout);
    var capture = StdoutCapture.start(process);

    capture.awaitEnd(Await.HANG_GUARD);

    assertFalse(process.reader.isAlive());
    assertEquals(0, stdout.available());
    assertEquals(
        printed.substring(0, ProcessTransport.MAX_CAPTURED_OUTPUT_CHARS)
            + "\n[output truncated: 2000000 more chars discarded]",
        capture.drain());
    assertEquals("", capture.drain());
  }

  @Test
  void outputIsDrainedWithoutWaitingForANewlineAndOnlyItsFinalNewlineIsDropped() {
    var stdout = new FeedableInputStream();
    var capture = StdoutCapture.start(new ReadRecordingProcess(stdout));

    stdout.feed("no newline yet");
    var drained = new AtomicReference<>("");
    Await.until(
        "the reader to capture the output",
        () -> {
          drained.set(capture.drain());
          return !drained.get().isEmpty();
        });
    assertEquals("no newline yet", drained.get());
    stdout.feed("blank line follows\n\n");
    stdout.end();
    capture.awaitEnd(Await.HANG_GUARD);

    assertEquals("blank line follows\n", capture.drain());
  }

  @Test
  void aCharacterSplitAcrossReadsIsDecodedWhole() {
    var stdout = new FeedableInputStream();
    var capture = StdoutCapture.start(new ReadRecordingProcess(stdout));
    var euro = "\u20AC".getBytes(StandardCharsets.UTF_8);

    stdout.feed(new byte[] {euro[0]});
    stdout.awaitBlockedRead();
    stdout.feed(new byte[] {euro[1], euro[2]});
    stdout.end();
    capture.awaitEnd(Await.HANG_GUARD);

    assertEquals("\u20AC", capture.drain());
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
