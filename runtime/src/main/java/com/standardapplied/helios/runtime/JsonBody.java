/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import io.helidon.http.Status;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads a request's JSON object body within a byte bound, and answers the request itself when it
 * cannot: {@code 413} for a body over the bound, refused unread when its {@code Content-Length}
 * declares it and otherwise as soon as one byte past the bound arrives, so no more than the bound
 * plus one byte is ever buffered; {@code 400} for a body that is not a JSON object or cannot be
 * read.
 */
final class JsonBody {

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
      return answered(resp, Status.BAD_REQUEST_400, INVALID);
    }
    return answered(resp, Status.REQUEST_ENTITY_TOO_LARGE_413, TOO_LARGE);
  }

  private static Optional<Map<String, Object>> answered(
      ServerResponse resp, Status status, Map<String, String> body) {
    resp.status(status).send(body);
    return Optional.empty();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(byte[] bytes, ObjectMapper mapper) {
    return (Map<String, Object>)
        Objects.requireNonNull(mapper.readValue(bytes, Map.class), "body is JSON null");
  }
}
