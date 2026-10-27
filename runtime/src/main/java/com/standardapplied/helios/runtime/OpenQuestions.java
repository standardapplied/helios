/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.runtime.AuthenticationGate.Caller;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import io.helidon.http.Status;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.util.List;
import java.util.Map;

/**
 * The work behind {@code GET .../questions} and {@code POST .../questions/{questionId}}: lists the
 * session's {@linkplain AgentSession#pendingQuestions() pending questions}, and answers one of them
 * from a JSON body {@code {selectedLabels: [...], customText: "..."}} read within {@link
 * RequestLimits#maxRequestBytes()}, keyed by the path's question id alone. The answer is {@code
 * 202} once the session accepted it, {@code 413} or {@code 400} for the body, {@code 401} if the
 * identity ended while the body uploaded, {@code 404} for a question not pending and {@code 409}
 * when the session is closed.
 */
final class OpenQuestions {

  private static final Map<String, String> INVALID_FIELDS =
      Map.of(
          "error",
          "'selectedLabels' must be an array of strings and 'customText', if set, a string");

  private final int maxRequestBytes;

  OpenQuestions(int maxRequestBytes) {
    this.maxRequestBytes = maxRequestBytes;
  }

  /** Answer {@code 200} with the session's pending questions as a JSON array. */
  void list(RegisteredSession session, Caller caller, ServerRequest req, ServerResponse resp) {
    JsonBody.send(resp, Status.OK_200, session.session().pendingQuestions());
  }

  /** Hand the body's response to the question the path names, or answer why not. */
  void answer(RegisteredSession session, Caller caller, ServerRequest req, ServerResponse resp) {
    var body = JsonBody.read(req, resp, maxRequestBytes, JsonBody.MAPPER);
    if (body.isEmpty()) {
      return;
    }
    if (!(body.get().get("selectedLabels") instanceof List<?> labels)
        || !labels.stream().allMatch(String.class::isInstance)
        || !(body.get().getOrDefault("customText", "") instanceof String customText)) {
      JsonBody.send(resp, Status.BAD_REQUEST_400, INVALID_FIELDS);
      return;
    }
    AskUserQuestionResponse response;
    try {
      response =
          new AskUserQuestionResponse(
              req.path().pathParameters().get("questionId"),
              labels.stream().map(String.class::cast).toList(),
              customText);
    } catch (IllegalArgumentException e) {
      JsonBody.send(resp, Status.BAD_REQUEST_400, Map.of("error", e.getMessage()));
      return;
    }
    if (caller.answeredAsEnded(resp)) {
      return;
    }
    try {
      session.session().answer(response.questionId(), response);
    } catch (IllegalArgumentException e) {
      JsonBody.send(resp, Status.NOT_FOUND_404, Map.of("error", e.getMessage()));
      return;
    } catch (IllegalStateException e) {
      JsonBody.send(resp, Status.CONFLICT_409, Map.of("error", e.getMessage()));
      return;
    }
    resp.status(Status.ACCEPTED_202).send();
  }
}
