/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.runtime.AuthenticationGate.Caller;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.SteeringQueueFullException;
import com.standardapplied.helios.session.UserMessage;
import io.helidon.http.Status;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.util.Map;
import java.util.function.BiConsumer;
import tools.jackson.databind.ObjectMapper;

/**
 * The work behind {@code POST .../messages} and {@code POST .../interrupt}: admits the upload
 * against the message-upload {@link ConcurrencyLimit} both routes share, reads the bounded JSON
 * body, takes one non-blank string field from it and hands it to the session's steering queue.
 * Answers {@code 202} once queued, {@code 429} or {@code 503} when the upload is refused, {@code
 * 413} or {@code 400} for the body, {@code 401} if the identity ended while the body uploaded,
 * {@code 429} when the queue is full and {@code 409} when the session can take no more messages.
 */
final class SteeringInput {

  private final AdmissionCounter counter = new AdmissionCounter();
  private final ConcurrencyLimit uploads;
  private final int maxRequestBytes;
  private final ObjectMapper objectMapper;

  SteeringInput(RequestLimits limits, ObjectMapper objectMapper) {
    this.uploads = ConcurrencyLimit.messageUploads(limits);
    this.maxRequestBytes = limits.maxRequestBytes();
    this.objectMapper = objectMapper;
  }

  /** Queue the body's {@code text} into {@code session} as a user message, or answer why not. */
  void send(AgentSession session, Caller caller, ServerRequest req, ServerResponse resp) {
    respond(
        "text", (target, text) -> target.send(UserMessage.text(text)), session, caller, req, resp);
  }

  /** Queue the body's {@code reason} into {@code session} as an interrupt, or answer why not. */
  void interrupt(AgentSession session, Caller caller, ServerRequest req, ServerResponse resp) {
    respond("reason", AgentSession::interrupt, session, caller, req, resp);
  }

  AdmissionCounts counts() {
    return counter.counts();
  }

  private void respond(
      String field,
      BiConsumer<AgentSession, String> action,
      AgentSession session,
      Caller caller,
      ServerRequest req,
      ServerResponse resp) {
    var admitted = false;
    try {
      admitted = uploads.admit(caller.principal(), resp);
      if (admitted) {
        readAndQueue(field, action, session, caller, req, resp);
      }
    } finally {
      settle(caller.principal(), admitted);
    }
  }

  /** Give back an admitted upload; count a refused one, even if refusing threw. */
  private void settle(Principal principal, boolean admitted) {
    if (admitted) {
      uploads.release(principal);
    } else {
      counter.reject();
    }
  }

  private void readAndQueue(
      String field,
      BiConsumer<AgentSession, String> action,
      AgentSession session,
      Caller caller,
      ServerRequest req,
      ServerResponse resp) {
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
