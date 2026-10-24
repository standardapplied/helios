/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webserver.http.ServerResponse;
import java.util.Map;

/**
 * The answers to a request refused for a limit: {@code 429} when the caller's own use reached it,
 * {@code 503} when the server's capacity did. Each has a fixed body that names the limit and
 * nothing else, and a {@code Retry-After} of {@value #RETRY_AFTER_SECONDS} seconds, so a client
 * backs off instead of retrying at once.
 */
final class Overload {

  /** Seconds a refused client is asked to wait before retrying. */
  static final String RETRY_AFTER_SECONDS = "5";

  static final Map<String, String> SESSION_QUOTA = Map.of("error", "session quota reached");

  static final Map<String, String> SERVER_AT_CAPACITY =
      Map.of("error", "server at session capacity");

  static final Map<String, String> QUEUE_FULL = Map.of("error", "session message queue full");

  static final Map<String, String> TOO_MANY_REQUESTS =
      Map.of("error", "too many requests in progress");

  private Overload() {}

  /** Refuse with {@code 429} or {@code 503}, by whose session cap {@code e} reports reached. */
  static void reject(ServerResponse resp, SessionCapacityException e) {
    if (e.scope() == SessionCapacityException.Scope.PRINCIPAL) {
      reject(resp, Status.TOO_MANY_REQUESTS_429, SESSION_QUOTA);
    } else {
      reject(resp, Status.SERVICE_UNAVAILABLE_503, SERVER_AT_CAPACITY);
    }
  }

  /** Refuse with {@code status}, {@code body} and the retry guidance. */
  static void reject(ServerResponse resp, Status status, Map<String, String> body) {
    resp.header(HeaderNames.RETRY_AFTER, RETRY_AFTER_SECONDS);
    JsonBody.send(resp, status, body);
  }
}
