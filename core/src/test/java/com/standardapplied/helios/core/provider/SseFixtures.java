/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Parts shared by {@link SseReaderTest} and {@link SseRequestTest}. */
final class SseFixtures {

  static final Duration NEVER_IDLE = Duration.ofMinutes(10);

  private SseFixtures() {}

  /**
   * Echoes each payload as text, completes the stream on "stop", fails it on "fail", and reports
   * the payloads so far as its partial response.
   */
  static class EchoParser implements SseReader.Parser {
    final List<String> payloads = new ArrayList<>();

    @Override
    public StreamEvent parse(String payload) {
      payloads.add(payload);
      return switch (payload) {
        case "stop" -> new StreamEvent.Done(Response.newBuilder().withContent("done").build());
        case "fail" -> new StreamEvent.Error("failed", null, partial());
        case "silent" -> null;
        default -> new StreamEvent.TextDelta(payload);
      };
    }

    @Override
    public Response<Void> partial() {
      return Response.newBuilder()
          .withContent(String.join("|", payloads))
          .withFinishReason(FinishReason.ERROR)
          .build();
    }
  }

  /**
   * Records whether the reader closed the body it owns, and every thread that read or closed it.
   */
  static final class Closing extends FilterInputStream {
    final Set<Thread> readers = ConcurrentHashMap.newKeySet();
    final List<Thread> closers = new CopyOnWriteArrayList<>();
    boolean closed;

    Closing(InputStream body) {
      super(body);
    }

    @Override
    public int read() throws IOException {
      readers.add(Thread.currentThread());
      return super.read();
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
      readers.add(Thread.currentThread());
      return super.read(target, offset, length);
    }

    @Override
    public void close() throws IOException {
      closers.add(Thread.currentThread());
      closed = true;
      super.close();
    }
  }

  static InputStream stream(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }
}
