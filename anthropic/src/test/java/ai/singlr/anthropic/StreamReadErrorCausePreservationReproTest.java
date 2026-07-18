/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.StreamEvent;
import ai.singlr.core.model.TransientStreamException;
import ai.singlr.core.tool.Tool;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regression coverage for the 2.5.5 Anthropic-layer change: a mid-stream {@link IOException} (the
 * wire shape the Light Grid bug report described) now surfaces as a {@link
 * TransientStreamException} carrying both the originating {@link IOException} cause and the
 * provider name. The session loop pattern-matches the exception type and applies bounded retry;
 * that orchestration lives in {@code helios-session}'s {@code TurnRunner} and is covered by {@code
 * session.StreamReadErrorReproTest}.
 *
 * <p>Three layers exercised here:
 *
 * <ol>
 *   <li>{@link AnthropicStreamingIterator} preserves the {@code IOException} as {@link
 *       StreamEvent.Error#cause()} after a partial SSE prefix has been delivered.
 *   <li>{@link AnthropicModel#chat(List, List)} promotes the iterator's {@code
 *       StreamEvent.Error(IOException)} to a {@link TransientStreamException} (instead of the old
 *       opaque {@link AnthropicException}), so the session loop can identify it without depending
 *       on provider-specific exception classes.
 *   <li>Non-stream paths — the API-side {@code event: error} carrying a {@code null} cause, and any
 *       non-{@code IOException} cause — remain on the {@link AnthropicException} path so the loop
 *       doesn't retry programmer errors.
 * </ol>
 */
class StreamReadErrorCausePreservationReproTest {

  private static final String MESSAGE_START =
      "event: message_start\n"
          + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\","
          + "\"type\":\"message\",\"role\":\"assistant\",\"content\":[],"
          + "\"model\":\"claude-sonnet-4-6-20250514\",\"stop_reason\":null,"
          + "\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}\n\n";

  private static final String TEXT_BLOCK_START =
      "event: content_block_start\n"
          + "data: {\"type\":\"content_block_start\",\"index\":0,"
          + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n";

  private static final String TEXT_DELTA =
      "event: content_block_delta\n"
          + "data: {\"type\":\"content_block_delta\",\"index\":0,"
          + "\"delta\":{\"type\":\"text_delta\",\"text\":\"partial-emit\"}}\n\n";

  private final tools.jackson.databind.ObjectMapper objectMapper =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  // ── Layer 1 — AnthropicStreamingIterator preserves IOException cause
  // ─────────────────────────────

  @org.junit.jupiter.api.Test
  void streamingIteratorPreservesIoExceptionAsCauseAfterPartialDelivery() {
    var prefix = (MESSAGE_START + TEXT_BLOCK_START + TEXT_DELTA).getBytes(StandardCharsets.UTF_8);
    var resetMessage = "Connection reset by peer";
    InputStream failingStream =
        new SequenceInputStream(
            new ByteArrayInputStream(prefix),
            new InputStream() {
              @Override
              public int read() throws IOException {
                throw new IOException(resetMessage);
              }
            });

    try (var iterator =
        new AnthropicStreamingIterator(
            fakeResponse(failingStream), objectMapper, Duration.ofSeconds(5))) {
      assertTrue(iterator.hasNext());
      assertInstanceOf(StreamEvent.TextDelta.class, iterator.next());

      assertTrue(iterator.hasNext());
      var error = assertInstanceOf(StreamEvent.Error.class, iterator.next());

      assertEquals("Stream read error", error.message());
      var cause = error.cause();
      assertNotNull(cause);
      assertEquals(IOException.class, cause.getClass());
      assertTrue(cause.getMessage().contains(resetMessage));
    }
  }

  @org.junit.jupiter.api.Test
  void streamErrorWithNullCauseIsAlsoLegalAndShouldBeHandledByDownstreamFix() {
    var apiErrorJson =
        "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\","
            + "\"message\":\"Overloaded\"}}\n\n";
    InputStream stream = new ByteArrayInputStream(apiErrorJson.getBytes(StandardCharsets.UTF_8));

    try (var iterator =
        new AnthropicStreamingIterator(fakeResponse(stream), objectMapper, Duration.ofSeconds(5))) {
      assertTrue(iterator.hasNext());
      var error = assertInstanceOf(StreamEvent.Error.class, iterator.next());
      assertTrue(error.message().startsWith("API stream error:"));
      assertNull(error.cause(), "API-side stream errors deliberately carry a null cause");
    }
  }

  // ── Layer 2 — AnthropicModel.chat surfaces stream-IO failures as TransientStreamException ──

  private ServerSocket serverSocket;
  private ExecutorService serverExecutor;
  private int port;

  @BeforeEach
  void startMidStreamResetServer() throws IOException {
    serverSocket = new ServerSocket(0);
    port = serverSocket.getLocalPort();
    serverExecutor = Executors.newSingleThreadExecutor();
    serverExecutor.submit(
        () -> {
          try {
            var client = serverSocket.accept();
            try {
              // SO_LINGER(true, 0) makes close() send a TCP RST instead of FIN. The client's
              // BufferedReader.readLine sees an IOException ("Connection reset") instead of a
              // graceful EOF — that's the wire condition we need to reproduce the bug.
              client.setSoLinger(true, 0);
              try (var in =
                  new java.io.BufferedReader(
                      new java.io.InputStreamReader(client.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                  // drain headers
                }
              }
              OutputStream out = client.getOutputStream();
              var headers =
                  "HTTP/1.1 200 OK\r\n"
                      + "Content-Type: text/event-stream\r\n"
                      + "Transfer-Encoding: chunked\r\n\r\n";
              out.write(headers.getBytes(StandardCharsets.US_ASCII));
              var bodyPrefix = MESSAGE_START + TEXT_BLOCK_START + TEXT_DELTA;
              var chunkLen = Integer.toHexString(bodyPrefix.length()) + "\r\n";
              out.write(chunkLen.getBytes(StandardCharsets.US_ASCII));
              out.write(bodyPrefix.getBytes(StandardCharsets.UTF_8));
              out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
              out.flush();
            } finally {
              client.close();
            }
          } catch (IOException ignored) {
            // expected when the connection is reset
          }
        });
  }

  @AfterEach
  void stopServer() throws IOException {
    if (serverSocket != null && !serverSocket.isClosed()) {
      serverSocket.close();
    }
    if (serverExecutor != null) {
      serverExecutor.shutdownNow();
    }
  }

  @org.junit.jupiter.api.Test
  void anthropicModelChatPromotesMidStreamIoExceptionToTransientStreamException() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withBaseUrl("http://localhost:" + port)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            Throwable.class,
            () -> model.chat(List.of(Message.user("hi")), List.<Tool>of()),
            "mid-stream socket drop must surface as a typed exception, not Success");
    var tse =
        assertInstanceOf(
            TransientStreamException.class,
            thrown,
            "post-2.5.5 the IOException-caused stream error promotes to TransientStreamException");
    assertEquals("anthropic", tse.providerName());
    assertNotNull(tse.getCause(), "underlying IOException is preserved as cause");
    assertInstanceOf(IOException.class, tse.getCause());
  }

  // ── Layer 3 — non-IOException causes stay on the AnthropicException path ─────────────────

  @org.junit.jupiter.api.Test
  void streamingIteratorParseFailureRemainsAnthropicExceptionNotTransientStream() {
    // A malformed SSE frame triggers parseStreamEvent's catch (Exception e) branch which yields
    // StreamEvent.Error("Failed to parse stream event", e). That cause is NOT an IOException, so
    // it must NOT be promoted to TransientStreamException (parser bugs and provider contract
    // violations are not transient).
    var malformed = "event: content_block_delta\ndata: {not-json}\n\n";
    InputStream stream = new ByteArrayInputStream(malformed.getBytes(StandardCharsets.UTF_8));

    try (var iterator =
        new AnthropicStreamingIterator(fakeResponse(stream), objectMapper, Duration.ofSeconds(5))) {
      assertTrue(iterator.hasNext());
      var error = assertInstanceOf(StreamEvent.Error.class, iterator.next());
      assertTrue(error.message().contains("Failed to parse"));
      assertNotNull(error.cause(), "the parse failure is preserved as the cause");
      assertFalse(
          error.cause() instanceof IOException,
          "the parse failure is a Jackson exception, not an IOException — only IOExceptions"
              + " get promoted to TransientStreamException for retry");
    }
  }

  private static HttpResponse<InputStream> fakeResponse(InputStream body) {
    return new HttpResponse<>() {
      @Override
      public int statusCode() {
        return 200;
      }

      @Override
      public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (a, b) -> true);
      }

      @Override
      public InputStream body() {
        return body;
      }

      @Override
      public Optional<HttpResponse<InputStream>> previousResponse() {
        return Optional.empty();
      }

      @Override
      public HttpRequest request() {
        return null;
      }

      @Override
      public URI uri() {
        return URI.create("https://test");
      }

      @Override
      public HttpClient.Version version() {
        return HttpClient.Version.HTTP_2;
      }

      @Override
      public Optional<SSLSession> sslSession() {
        return Optional.empty();
      }
    };
  }
}
