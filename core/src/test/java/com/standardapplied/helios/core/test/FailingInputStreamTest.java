/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class FailingInputStreamTest {

  @Test
  void everyReadThrowsTheCheckedFailure() {
    var failure = new IOException("network down");
    var stream = FailingInputStream.onRead(failure);

    assertSame(failure, assertThrows(IOException.class, stream::read));
    assertSame(failure, assertThrows(IOException.class, () -> stream.read(new byte[8])));
  }

  @Test
  void everyReadThrowsTheUncheckedFailure() {
    var failure = new IllegalStateException("unexpected");
    var stream = FailingInputStream.onRead(failure);

    assertSame(failure, assertThrows(IllegalStateException.class, stream::read));
    assertSame(failure, assertThrows(IllegalStateException.class, stream::read));
  }

  @Test
  void aStreamFailingOnCloseIsEmptyAndThrowsWhenClosed() throws IOException {
    var failure = new IOException("close failure");
    var stream = FailingInputStream.onClose(failure);

    assertEquals(-1, stream.read());
    assertSame(failure, assertThrows(IOException.class, stream::close));
  }

  @Test
  void aStreamFailingOnReadClosesQuietly() throws IOException {
    FailingInputStream.onRead(new IOException("network down")).close();
  }
}
