/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import java.io.IOException;
import java.io.InputStream;

/**
 * An {@link InputStream} that fails at once: every read throws the failure it was built with, or,
 * built by {@link #onClose}, every read finds the stream ended and closing it throws.
 */
public final class FailingInputStream extends InputStream {

  @FunctionalInterface
  private interface Action {
    void run() throws IOException;
  }

  private static final Action NOTHING = () -> {};

  private final Action beforeRead;
  private final Action onClose;

  private FailingInputStream(Action beforeRead, Action onClose) {
    this.beforeRead = beforeRead;
    this.onClose = onClose;
  }

  /** A stream whose every read throws {@code failure}. */
  public static FailingInputStream onRead(IOException failure) {
    return new FailingInputStream(
        () -> {
          throw failure;
        },
        NOTHING);
  }

  /** A stream whose every read throws {@code failure}, which no well-behaved stream throws. */
  public static FailingInputStream onRead(RuntimeException failure) {
    return new FailingInputStream(
        () -> {
          throw failure;
        },
        NOTHING);
  }

  /** An empty stream whose {@link #close()} throws {@code failure}. */
  public static FailingInputStream onClose(IOException failure) {
    return new FailingInputStream(
        NOTHING,
        () -> {
          throw failure;
        });
  }

  @Override
  public int read() throws IOException {
    beforeRead.run();
    return -1;
  }

  @Override
  public void close() throws IOException {
    onClose.run();
  }
}
