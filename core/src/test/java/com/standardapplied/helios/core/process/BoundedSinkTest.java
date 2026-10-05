/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.process;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BoundedSinkTest {

  /** A child's pipe fails once the child is killed; what arrived before stays captured. */
  @Test
  void streamFailingMidDrainKeepsWhatArrived() {
    var sink = new BoundedSink(100);

    sink.drain(new FailingAfter("partial".getBytes(StandardCharsets.UTF_8)));

    assertArrayEquals("partial".getBytes(StandardCharsets.UTF_8), sink.bytes());
    assertFalse(sink.truncated());
  }

  private static final class FailingAfter extends InputStream {
    private final byte[] data;
    private int position;

    FailingAfter(byte[] data) {
      this.data = data;
    }

    @Override
    public int read() throws IOException {
      if (position == data.length) {
        throw new IOException("Stream closed");
      }
      return data[position++];
    }
  }
}
