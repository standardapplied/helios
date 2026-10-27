/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.runtime.AuthenticationGate.Caller;
import com.standardapplied.helios.session.SteeringQueueFullException;
import com.standardapplied.helios.session.UserMessage;
import io.helidon.http.Status;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * The work behind {@code POST .../messages} and {@code POST .../interrupt}. A message: reads the
 * JSON body within {@link RequestLimits#maxRequestBytes()}, takes its non-blank {@code text} and
 * sends it to the {@link RegisteredSession}, which steers the run in progress or starts the next
 * one; answers {@code 202} once sent, {@code 413} or {@code 400} for the body, {@code 401} if the
 * identity ended while the body uploaded, {@code 429} when the queue is full and {@code 409} when
 * the session closed after it was looked up. An interrupt takes no body, cancels the run in
 * progress, if any, and answers {@code 202}.
 */
final class SteeringInput {

  private final int maxRequestBytes;
  private final ObjectMapper objectMapper;

  SteeringInput(int maxRequestBytes, ObjectMapper objectMapper) {
    this.maxRequestBytes = maxRequestBytes;
    this.objectMapper = objectMapper;
  }

  /** Send the body's {@code text} to {@code session} as a user message, or answer why not. */
  void send(RegisteredSession session, Caller caller, ServerRequest req, ServerResponse resp) {
    var body = JsonBody.read(req, resp, maxRequestBytes, objectMapper);
    if (body.isEmpty()) {
      return;
    }
    if (!(body.get().get("text") instanceof String text) || Strings.isBlank(text)) {
      JsonBody.send(
          resp, Status.BAD_REQUEST_400, Map.of("error", "'text' field must be a non-blank string"));
      return;
    }
    if (caller.answeredAsEnded(resp)) {
      return;
    }
    try {
      session.send(UserMessage.text(text));
    } catch (SteeringQueueFullException e) {
      Overload.reject(resp, Status.TOO_MANY_REQUESTS_429, Overload.QUEUE_FULL);
      return;
    } catch (IllegalStateException e) {
      JsonBody.send(resp, Status.CONFLICT_409, Map.of("error", e.getMessage()));
      return;
    }
    resp.status(Status.ACCEPTED_202).send();
  }

  /** Cancel {@code session}'s run in progress, if any. */
  void interrupt(RegisteredSession session, Caller caller, ServerRequest req, ServerResponse resp) {
    session.session().interrupt();
    resp.status(Status.ACCEPTED_202).send();
  }
}
