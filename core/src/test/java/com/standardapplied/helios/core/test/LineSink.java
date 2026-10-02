/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * An {@link OutputStream} that queues what the code under test writes as whole UTF-8 lines, for a
 * test to take one at a time. Any number of threads may write, and a writer thread may exit at any
 * moment: unlike a {@code PipedInputStream}, which fails with "Write end dead" when its reader
 * arrives between two short-lived writers, a queued line stays readable.
 */
public final class LineSink extends OutputStream {

  private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
  private final ByteArrayOutputStream partial = new ByteArrayOutputStream();

  @Override
  public synchronized void write(int b) {
    if (b == '\n') {
      lines.add(partial.toString(StandardCharsets.UTF_8));
      partial.reset();
    } else {
      partial.write(b);
    }
  }

  /** The next complete line, without its terminator, waiting through {@link Await} for it. */
  public String nextLine() {
    return Await.next("the next line written to the sink", lines);
  }
}
