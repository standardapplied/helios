/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.process;

import com.standardapplied.helios.core.common.Redactor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Thread-safe capture of one process stream that keeps at most a fixed number of bytes, drops the
 * rest, and when it dropped anything, or stopped waiting for the stream's end, trims a secret the
 * cut went through off what it kept and appends a truncation marker.
 */
final class BoundedSink {

  private static final byte[] TRUNCATION_MARKER =
      "\n[truncated: output exceeded cap]".getBytes(StandardCharsets.US_ASCII);

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final int max;
  private boolean truncated;
  private Thread drainer;

  BoundedSink(int max) {
    this.max = max;
  }

  /** A sink of at most {@code max} bytes that drains {@code in} on a virtual thread of its own. */
  static BoundedSink draining(InputStream in, int max) {
    var sink = new BoundedSink(max);
    sink.drainer = Thread.startVirtualThread(() -> sink.drain(in));
    return sink;
  }

  /**
   * Holds {@code in}'s monitor for the whole drain. When the child exits, the JDK's reaper swaps
   * the pipe for what is buffered and closes it under that monitor ({@code
   * ProcessPipeInputStream.processExited}), though a descendant may still write to it; holding the
   * monitor keeps the pipe open until it really ends.
   */
  void drain(InputStream in) {
    var buf = new byte[8192];
    try (in) {
      synchronized (in) {
        int n;
        while ((n = in.read(buf)) >= 0) {
          write(buf, 0, n);
        }
      }
    } catch (IOException ignored) {
      // The stream closes when the process terminates.
    }
  }

  synchronized void write(byte[] buf, int off, int len) {
    var remaining = max - out.size();
    if (remaining <= 0) {
      truncated = true;
      return;
    }
    var toWrite = Math.min(len, remaining);
    out.write(buf, off, toWrite);
    if (toWrite < len) {
      truncated = true;
    }
  }

  /**
   * Wait until the drain reaches the end of its stream or {@code deadline}, a {@link
   * System#nanoTime()} value, passes. A drain still running then is abandoned: what the sink kept
   * is marked as truncated. An interrupt pending on entry throws even when the drain has already
   * ended, so one that a reaping kept for the caller is not lost.
   */
  void awaitEnd(long deadline) throws InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException("Interrupted while awaiting the end of a process stream");
    }
    if (!drainer.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())))) {
      markTruncated();
    }
  }

  private synchronized void markTruncated() {
    truncated = true;
  }

  synchronized byte[] bytes(Redactor redactor) {
    var raw = out.toByteArray();
    if (!truncated) {
      return raw;
    }
    raw = redactor.trimPartialSecret(raw);
    var combined = new byte[raw.length + TRUNCATION_MARKER.length];
    System.arraycopy(raw, 0, combined, 0, raw.length);
    System.arraycopy(TRUNCATION_MARKER, 0, combined, raw.length, TRUNCATION_MARKER.length);
    return combined;
  }

  synchronized boolean truncated() {
    return truncated;
  }
}
