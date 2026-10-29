/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import io.helidon.http.HeaderValues;
import io.helidon.http.RequestException;
import io.helidon.http.Status;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * The runtime's one JSON place: the one fixed {@link #MAPPER} every route writes with, so the wire
 * format is the same on any Helidon server and needs no media support registered on it.
 *
 * <p>{@link #read} reads a request's JSON object body within a byte bound, and answers the request
 * itself when it cannot: {@code 413} for a body over the bound, refused unread when its {@code
 * Content-Length} declares it and otherwise as soon as one byte past the bound arrives, or as soon
 * as the server's own {@code maxPayloadSize} refuses it, so no more than the bound plus one byte is
 * ever buffered; {@code 400} for a body that is not a JSON object or cannot be read.
 */
final class JsonBody {

  /** Writes every body the runtime sends, tool and hook outcomes included, in camelCase. */
  static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .addModule(
              new SimpleModule("helios-outcomes")
                  .addSerializer(new ToolOutcomeSerializer())
                  .addSerializer(new HookOutcomeSerializer()))
          .build();

  private static final Map<String, String> TOO_LARGE = Map.of("error", "request body too large");

  private static final Map<String, String> INVALID = Map.of("error", "invalid JSON body");

  private JsonBody() {}

  /**
   * Read {@code req}'s body as a JSON object of at most {@code maxBytes} bytes.
   *
   * @return the object; empty when the request has been answered instead
   */
  static Optional<Map<String, Object>> read(
      ServerRequest req, ServerResponse resp, int maxBytes, ObjectMapper mapper) {
    if (req.headers().contentLength().orElse(0L) > maxBytes) {
      return answered(resp, Status.REQUEST_ENTITY_TOO_LARGE_413, TOO_LARGE);
    }
    try {
      var bytes = req.content().inputStream().readNBytes(maxBytes + 1);
      if (bytes.length <= maxBytes) {
        return Optional.of(parse(bytes, mapper));
      }
    } catch (IOException | RuntimeException e) {
      return refusedAsTooLarge(e)
          ? answered(resp, Status.REQUEST_ENTITY_TOO_LARGE_413, TOO_LARGE)
          : answered(resp, Status.BAD_REQUEST_400, INVALID);
    }
    return answered(resp, Status.REQUEST_ENTITY_TOO_LARGE_413, TOO_LARGE);
  }

  /**
   * Answer {@code resp} with {@code status} and {@code body} written by {@link #MAPPER} as UTF-8
   * JSON.
   */
  static void send(ServerResponse resp, Status status, Object body) {
    resp.header(HeaderValues.CONTENT_TYPE_JSON);
    resp.status(status).send(MAPPER.writeValueAsBytes(body));
  }

  private static Optional<Map<String, Object>> answered(
      ServerResponse resp, Status status, Map<String, String> body) {
    send(resp, status, body);
    return Optional.empty();
  }

  /**
   * Whether the server refused the body as over its {@code maxPayloadSize}, which Helidon reports
   * mid-stream for a chunked body.
   */
  private static boolean refusedAsTooLarge(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof RequestException e
          && e.status().code() == Status.REQUEST_ENTITY_TOO_LARGE_413.code()) {
        return true;
      }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(byte[] bytes, ObjectMapper mapper) {
    return (Map<String, Object>)
        Objects.requireNonNull(mapper.readValue(bytes, Map.class), "body is JSON null");
  }
}
