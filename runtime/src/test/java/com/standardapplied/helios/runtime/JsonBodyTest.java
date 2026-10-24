/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.standardapplied.helios.runtime.ServerStubs.RecordingResponse;
import io.helidon.http.DirectHandler.EventType;
import io.helidon.http.RequestException;
import io.helidon.http.ServerRequestHeaders;
import io.helidon.http.Status;
import io.helidon.http.media.ReadableEntity;
import io.helidon.webserver.http.ServerRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * {@link JsonBody#read} answers a body over its bound {@code 413} whether the request declares it,
 * the server refuses it as over its own {@code maxPayloadSize}, or it simply runs past the bound,
 * and any other failure to read or parse it {@code 400}; every answer is written by the one mapper.
 */
final class JsonBodyTest {

  private static final int MAX_BYTES = 16;
  private static final String TOO_LARGE = "{\"error\":\"request body too large\"}";
  private static final String INVALID = "{\"error\":\"invalid JSON body\"}";

  private final RecordingResponse resp = new RecordingResponse();

  @Test
  void aBodyWithinTheBoundIsReadAsAJsonObject() {
    var body = read(OptionalLong.empty(), bytes("{\"text\":\"hi\"}"));

    assertEquals(Optional.of(Map.of("text", "hi")), body);
    assertFalse(resp.isSent());
  }

  @Test
  void aBodyDeclaredOverTheBoundIsAnswered413Unread() {
    var body =
        read(
            OptionalLong.of(MAX_BYTES + 1),
            failing(new IllegalStateException("the body was read")));

    assertAnswered(Status.REQUEST_ENTITY_TOO_LARGE_413, TOO_LARGE, body);
  }

  @Test
  void aBodyRunningPastTheBoundIsAnswered413() {
    var body = read(OptionalLong.empty(), bytes("{\"text\":\"" + "x".repeat(MAX_BYTES) + "\"}"));

    assertAnswered(Status.REQUEST_ENTITY_TOO_LARGE_413, TOO_LARGE, body);
  }

  @Test
  void aBodyTheServerRefusesAsTooLargeIsAnswered413() {
    var body = read(OptionalLong.empty(), failing(refused(Status.REQUEST_ENTITY_TOO_LARGE_413)));

    assertAnswered(Status.REQUEST_ENTITY_TOO_LARGE_413, TOO_LARGE, body);
  }

  @Test
  void aBodyTheServerRefusesAsTooLargeBehindAnotherFailureIsAnswered413() {
    var wrapped =
        new UncheckedIOException(
            new IOException("chunk read failed", refused(Status.REQUEST_ENTITY_TOO_LARGE_413)));

    var body = read(OptionalLong.empty(), failing(wrapped));

    assertAnswered(Status.REQUEST_ENTITY_TOO_LARGE_413, TOO_LARGE, body);
  }

  @Test
  void aBodyTheServerRefusesForAnotherReasonIsAnswered400() {
    var body = read(OptionalLong.empty(), failing(refused(Status.BAD_REQUEST_400)));

    assertAnswered(Status.BAD_REQUEST_400, INVALID, body);
  }

  @Test
  void aBodyThatCannotBeReadIsAnswered400() {
    var body = read(OptionalLong.empty(), failing(new UncheckedIOException(new IOException("x"))));

    assertAnswered(Status.BAD_REQUEST_400, INVALID, body);
  }

  @Test
  void aBodyThatIsNotAJsonObjectIsAnswered400() {
    var body = read(OptionalLong.empty(), bytes("null"));

    assertAnswered(Status.BAD_REQUEST_400, INVALID, body);
  }

  @Test
  void anAnswerIsJsonWrittenAsUtf8() {
    JsonBody.send(resp.response, Status.CONFLICT_409, Map.of("error", "déjà"));

    assertEquals(Status.CONFLICT_409, resp.status());
    assertEquals("application/json", resp.header("Content-Type"));
    assertEquals("{\"error\":\"déjà\"}", resp.body());
  }

  private Optional<Map<String, Object>> read(OptionalLong contentLength, InputStream body) {
    var headers =
        ServerStubs.stub(
            ServerRequestHeaders.class,
            (name, args) -> {
              assertEquals("contentLength", name);
              return contentLength;
            });
    var entity =
        ServerStubs.stub(
            ReadableEntity.class,
            (name, args) -> {
              assertEquals("inputStream", name);
              return body;
            });
    var req =
        ServerStubs.stub(
            ServerRequest.class, (name, args) -> name.equals("headers") ? headers : entity);
    return JsonBody.read(req, resp.response, MAX_BYTES, JsonBody.MAPPER);
  }

  private void assertAnswered(Status status, String json, Optional<Map<String, Object>> body) {
    assertEquals(Optional.empty(), body);
    assertEquals(status, resp.status());
    assertEquals("application/json", resp.header("Content-Type"));
    assertEquals(json, resp.body());
  }

  private static InputStream bytes(String body) {
    return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
  }

  /** A body whose first read throws {@code failure}. */
  private static InputStream failing(RuntimeException failure) {
    return new InputStream() {
      @Override
      public int read() {
        throw failure;
      }

      @Override
      public int read(byte[] b, int off, int len) {
        throw failure;
      }
    };
  }

  private static RequestException refused(Status status) {
    return RequestException.builder()
        .type(EventType.BAD_REQUEST)
        .status(status)
        .message("refused")
        .build();
  }
}
