/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.runtime;

import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.runtime.AuthenticationGate.Caller;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import io.helidon.http.Status;
import io.helidon.webserver.http.Handler;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import tools.jackson.databind.ObjectMapper;

/**
 * Helidon {@link HttpService} that exposes one {@link SessionRegistry}'s sessions over HTTP. The
 * six routes mirror the spec §15.1 sketch and are mounted by the caller under whatever prefix fits
 * the deployment ({@code routing.register("/v1", new AgentHttpService(...))}). {@link
 * RuntimeServer} is the embedding that does this for you.
 *
 * <ul>
 *   <li>{@code POST /sessions} — create a fresh session; returns {@code {sessionId, eventsUrl}}
 *       with {@code 201 Created}.
 *   <li>{@code POST /sessions/{sessionId}/messages} — body {@code {text: "..."}}; queues the
 *       message and returns {@code 202 Accepted}.
 *   <li>{@code POST /sessions/{sessionId}/interrupt} — body {@code {reason: "..."}}; queues a
 *       synthetic interrupt message and returns {@code 202 Accepted}.
 *   <li>{@code GET /sessions/{sessionId}/events} — opens an SSE stream of {@link QueryEvent}s. The
 *       handler blocks the request thread until the publisher signals {@code onComplete}, the
 *       client disconnects, or the caller's identity ends.
 *   <li>{@code GET /sessions/{sessionId}/result?timeout=<seconds>} — long-poll for the terminal
 *       {@link com.standardapplied.helios.session.ResultMessage ResultMessage}. Returns {@code 200
 *       OK} with body {@code {type: "<SubtypeName>", result: <record-fields>}} when terminal;
 *       {@code 204 No Content} when the {@code timeout} elapses with no terminal. {@code timeout}
 *       defaults to 60 s and is clamped to {@code [0, 300]} so a single request cannot pin a server
 *       thread longer than five minutes.
 *   <li>{@code DELETE /sessions/{sessionId}} — closes and unregisters the session; returns {@code
 *       204 No Content}.
 * </ul>
 *
 * <h2>Authentication and ownership</h2>
 *
 * Every route runs behind the one {@link Authenticator} before it reads the body, looks up a
 * session, subscribes or queues work: a request without a valid identity is answered {@code 401}
 * (the authenticator failing, {@code 503}) and nothing else happens. A session is owned by the
 * {@link Principal} that created it. A request for another principal's session is answered with the
 * same {@code 404} as an unknown id, so ids of other users and tenants are never disclosed. An SSE
 * stream ends, and a pending result long poll answers {@code 401}, as soon as the caller's identity
 * expires or is revoked (see {@link Authentication}). A route added to this service is registered
 * through the same wrapper, never a second check.
 *
 * <p>The {@code optionsFactory} is the seam through which deployments choose how a new session is
 * configured: the runtime takes the generated session id and produces a fully-populated {@link
 * SessionOptions} for exactly that id; options for any other id are refused, so a factory can never
 * attach a request to another session. Error bodies carry a fixed message and never an exception's.
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. Each request runs on its own virtual thread; {@link SessionRegistry} synchronises
 * shared session state.
 */
public final class AgentHttpService implements HttpService {

  private static final Logger LOGGER = Logger.getLogger(AgentHttpService.class.getName());

  private static final Map<String, String> NOT_FOUND = Map.of("error", "session not found");

  private static final Map<String, String> FACTORY_FAILED =
      Map.of("error", "session options factory failed");

  private static final Map<String, String> INTERNAL_ERROR =
      Map.of("error", "internal server error");

  private final SessionRegistry registry;
  private final Function<String, SessionOptions> optionsFactory;
  private final AuthenticationGate gate;
  private final ObjectMapper objectMapper;
  private final String eventsPathPrefix;

  /**
   * Build a service.
   *
   * @param registry registry of live sessions; non-null
   * @param optionsFactory function that maps a generated session id to a fully-configured {@link
   *     SessionOptions} for that id; non-null
   * @param authenticator verifies every request's identity; non-null
   * @param objectMapper mapper for request/response bodies; non-null. The service uses a copy that
   *     also writes {@link com.standardapplied.helios.core.tool.ToolOutcome}
   * @param eventsPathPrefix prefix used to build the {@code eventsUrl} returned by {@code POST
   *     /sessions} (e.g. {@code "/v1"}); non-null
   * @throws NullPointerException if any argument is null
   */
  public AgentHttpService(
      SessionRegistry registry,
      Function<String, SessionOptions> optionsFactory,
      Authenticator authenticator,
      ObjectMapper objectMapper,
      String eventsPathPrefix) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.optionsFactory = Objects.requireNonNull(optionsFactory, "optionsFactory must not be null");
    this.gate = new AuthenticationGate(authenticator);
    this.objectMapper =
        ToolOutcomeSerializer.addTo(
            Objects.requireNonNull(objectMapper, "objectMapper must not be null"));
    this.eventsPathPrefix =
        Objects.requireNonNull(eventsPathPrefix, "eventsPathPrefix must not be null");
  }

  @Override
  public void routing(HttpRules rules) {
    rules.post("/sessions", authenticated(this::create));
    rules.post(
        "/sessions/{sessionId}/messages",
        authenticated(
            owned(queueText("text", (session, text) -> session.send(UserMessage.text(text))))));
    rules.post(
        "/sessions/{sessionId}/interrupt",
        authenticated(owned(queueText("reason", AgentSession::interrupt))));
    rules.get(
        "/sessions/{sessionId}/events",
        authenticated(
            owned(
                (session, caller, req, resp) ->
                    SessionEventStream.stream(session, resp, objectMapper, caller.ended()))));
    rules.get(
        "/sessions/{sessionId}/result",
        authenticated(
            owned(
                (session, caller, req, resp) ->
                    ResultLongPoll.respond(session, req, resp, objectMapper, caller.ended()))));
    rules.delete("/sessions/{sessionId}", authenticated(this::delete));
  }

  private Handler authenticated(CallerHandler handler) {
    return (req, resp) -> {
      var caller = gate.admit(req, resp);
      if (caller.isEmpty()) {
        return;
      }
      try {
        handler.handle(caller.get(), req, resp);
      } catch (RuntimeException e) {
        answerFailure(resp, e);
      } finally {
        caller.get().release();
      }
    };
  }

  /**
   * Answer a route that failed with a fixed {@code 500}; Helidon's default error handling would
   * send the exception's message to the client.
   */
  private static void answerFailure(ServerResponse resp, RuntimeException e) {
    LOGGER.log(Level.WARNING, "request failed", e);
    resp.status(Status.INTERNAL_SERVER_ERROR_500).send(INTERNAL_ERROR);
  }

  private CallerHandler owned(SessionHandler handler) {
    return (caller, req, resp) -> {
      var session = registry.find(sessionId(req), caller.principal());
      if (session.isEmpty()) {
        resp.status(Status.NOT_FOUND_404).send(NOT_FOUND);
        return;
      }
      handler.handle(session.get(), caller, req, resp);
    };
  }

  private void create(Caller caller, ServerRequest req, ServerResponse resp) {
    var sessionId = "sess-" + Ids.newId();
    SessionOptions options;
    try {
      options = optionsFactory.apply(sessionId);
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "optionsFactory failed for session " + sessionId, e);
      resp.status(Status.INTERNAL_SERVER_ERROR_500).send(FACTORY_FAILED);
      return;
    }
    if (options == null || !options.sessionId().equals(sessionId)) {
      LOGGER.warning("optionsFactory did not return options for session " + sessionId);
      resp.status(Status.INTERNAL_SERVER_ERROR_500).send(FACTORY_FAILED);
      return;
    }
    if (identityEnded(caller, resp)) {
      return;
    }
    registry.create(options, caller.principal());
    var created = new LinkedHashMap<String, String>();
    created.put("sessionId", sessionId);
    created.put("eventsUrl", eventsPathPrefix + "/sessions/" + sessionId + "/events");
    resp.status(Status.CREATED_201).send(created);
  }

  private SessionHandler queueText(String field, BiConsumer<AgentSession, String> action) {
    return (session, caller, req, resp) -> {
      Map<String, Object> body;
      try {
        body = readJsonBody(req);
      } catch (JacksonRuntimeException e) {
        resp.status(Status.BAD_REQUEST_400).send(Map.of("error", "invalid JSON body"));
        return;
      }
      if (!(body.get(field) instanceof String text) || Strings.isBlank(text)) {
        resp.status(Status.BAD_REQUEST_400)
            .send(Map.of("error", "'" + field + "' field must be a non-blank string"));
        return;
      }
      if (identityEnded(caller, resp)) {
        return;
      }
      try {
        action.accept(session, text);
      } catch (IllegalStateException e) {
        resp.status(Status.CONFLICT_409).send(Map.of("error", e.getMessage()));
        return;
      }
      resp.status(Status.ACCEPTED_202).send();
    };
  }

  private void delete(Caller caller, ServerRequest req, ServerResponse resp) {
    if (!registry.close(sessionId(req), caller.principal())) {
      resp.status(Status.NOT_FOUND_404).send(NOT_FOUND);
      return;
    }
    resp.status(Status.NO_CONTENT_204).send();
  }

  /**
   * Answer {@code 401} if the caller's identity ended while the request waited, on a slow body or
   * options factory, so no work is queued for an expired or revoked identity.
   */
  private static boolean identityEnded(Caller caller, ServerResponse resp) {
    if (caller.valid()) {
      return false;
    }
    resp.status(Status.UNAUTHORIZED_401).send(AuthenticationGate.UNAUTHENTICATED);
    return true;
  }

  private static String sessionId(ServerRequest req) {
    return req.path().pathParameters().get("sessionId");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readJsonBody(ServerRequest req) {
    try {
      return (Map<String, Object>)
          Objects.requireNonNull(req.content().as(Map.class), "body is JSON null");
    } catch (RuntimeException e) {
      throw new JacksonRuntimeException("failed to parse request body", e);
    }
  }

  /** A route's work once its caller is admitted. */
  @FunctionalInterface
  private interface CallerHandler {
    void handle(Caller caller, ServerRequest req, ServerResponse resp);
  }

  /** A route's work once its caller is admitted and the session is found among the caller's own. */
  @FunctionalInterface
  private interface SessionHandler {
    void handle(AgentSession session, Caller caller, ServerRequest req, ServerResponse resp);
  }
}
