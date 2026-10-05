/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Thread-safe capture of one process stream that keeps at most a fixed number of bytes, drops the
 * rest, and appends a truncation marker to what it kept when it dropped anything.
 */
final class BoundedSink {

  private static final byte[] TRUNCATION_MARKER =
      "\n[truncated: output exceeded cap]".getBytes(StandardCharsets.US_ASCII);

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final int max;
  private boolean truncated;

  BoundedSink(int max) {
    this.max = max;
  }

  void drain(InputStream in) {
    var buf = new byte[8192];
    try (in) {
      int n;
      while ((n = in.read(buf)) >= 0) {
        write(buf, 0, n);
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

  synchronized byte[] bytes() {
    var raw = out.toByteArray();
    if (!truncated) {
      return raw;
    }
    var combined = new byte[raw.length + TRUNCATION_MARKER.length];
    System.arraycopy(raw, 0, combined, 0, raw.length);
    System.arraycopy(TRUNCATION_MARKER, 0, combined, raw.length, TRUNCATION_MARKER.length);
    return combined;
  }

  synchronized boolean truncated() {
    return truncated;
  }
}
