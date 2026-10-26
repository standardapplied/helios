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

  /** Records whether the reader closed the body it owns. */
  static final class Closing extends FilterInputStream {
    boolean closed;

    Closing(InputStream body) {
      super(body);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }

  static InputStream stream(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }
}
