/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.runtime.AuthenticationGate.Caller;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.SteeringQueueFullException;
import io.helidon.http.Status;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.util.Map;
import java.util.function.BiConsumer;
import tools.jackson.databind.ObjectMapper;

/**
 * The work behind {@code POST .../messages} and {@code POST .../interrupt}: reads the bounded JSON
 * body, takes one non-blank string field from it and hands it to the session's steering queue.
 * Answers {@code 202} once queued, {@code 413} or {@code 400} for the body, {@code 401} if the
 * identity ended while the body uploaded, {@code 429} when the queue is full and {@code 409} when
 * the session can take no more messages.
 */
final class SteeringInput {

  private final String field;
  private final BiConsumer<AgentSession, String> action;
  private final AdmissionCounter counter;
  private final int maxRequestBytes;
  private final ObjectMapper objectMapper;

  SteeringInput(
      String field,
      BiConsumer<AgentSession, String> action,
      AdmissionCounter counter,
      int maxRequestBytes,
      ObjectMapper objectMapper) {
    this.field = field;
    this.action = action;
    this.counter = counter;
    this.maxRequestBytes = maxRequestBytes;
    this.objectMapper = objectMapper;
  }

  /** Queue the request's text into {@code session}, or answer why not. */
  void respond(AgentSession session, Caller caller, ServerRequest req, ServerResponse resp) {
    var body = JsonBody.read(req, resp, maxRequestBytes, objectMapper);
    if (body.isEmpty()) {
      return;
    }
    if (!(body.get().get(field) instanceof String text) || Strings.isBlank(text)) {
      resp.status(Status.BAD_REQUEST_400)
          .send(Map.of("error", "'" + field + "' field must be a non-blank string"));
      return;
    }
    if (caller.answeredAsEnded(resp)) {
      return;
    }
    try {
      action.accept(session, text);
    } catch (SteeringQueueFullException e) {
      counter.reject();
      Overload.reject(resp, Status.TOO_MANY_REQUESTS_429, Overload.QUEUE_FULL);
      return;
    } catch (IllegalStateException e) {
      resp.status(Status.CONFLICT_409).send(Map.of("error", e.getMessage()));
      return;
    }
    counter.admit();
    resp.status(Status.ACCEPTED_202).send();
  }
}
