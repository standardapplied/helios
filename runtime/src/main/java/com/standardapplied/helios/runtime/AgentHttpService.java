/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.runtime;

import com.standardapplied.helios.runtime.AuthenticationGate.Caller;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionOptions;
import io.helidon.http.HttpException;
import io.helidon.http.Status;
import io.helidon.webserver.http.Handler;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.util.Map;
import java.util.Objects;
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
 *       message and returns {@code 202 Accepted}; {@code 429} when the session's steering queue is
 *       full.
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
 * with the authenticator's {@link Authenticator#challenge()} in {@code WWW-Authenticate} (the
 * authenticator failing, {@code 503}) and nothing else happens. A session is owned by the {@link
 * Principal} that created it. A request for another principal's session is answered with the same
 * {@code 404} as an unknown id, so ids of other users and tenants are never disclosed. An SSE
 * stream ends, and a pending result long poll answers {@code 401}, as soon as the caller's identity
 * expires or is revoked (see {@link Authentication}). A route added to this service is registered
 * through the same wrapper, never a second check.
 *
 * <h2>Admission</h2>
 *
 * Every request that would hold memory is admitted against a bound before the work starts, and a
 * refused one is answered at once, never queued. {@code POST /sessions} reserves capacity in the
 * {@link SessionRegistry} before the {@code optionsFactory} runs: a full cap of the caller's own is
 * {@code 429}, the registry's {@code 503}. A message or interrupt body over {@link
 * RequestLimits#maxRequestBytes()} is {@code 413}; a message or interrupt beyond the caller's own
 * concurrent uploads in {@link RequestLimits} is {@code 429}, beyond the service's {@code 503}; one
 * refused by a full steering queue is {@code 429}; an event stream or result poll beyond the
 * session's or the caller's own limit in {@link RequestLimits} is {@code 429}, beyond the service's
 * {@code 503}, and holds its admission until it is answered even if its session is deleted. Each
 * {@code 429} and {@code 503} has a fixed body and a {@code Retry-After} header; {@link
 * #counters()} reports what was admitted and refused.
 *
 * <h2>Unread bodies</h2>
 *
 * Every route applies the {@link UnreadBodyGuard}: an answer sent while the request's body is not
 * read to its end, such as a {@code 401}, {@code 404}, {@code 413}, {@code 429} or {@code 503}
 * refused before the body, or a {@code 201} for a create that sent one, is written whole and then
 * ends the connection, so nothing the client sends after it is drained or run as a request. On a
 * {@link io.helidon.webserver.WebServer} of your own, also install the guard on its routing ({@code
 * routing.addFeature(new UnreadBodyGuard())}) so Helidon's own answers there, an unmatched path or
 * an exception a route lets escape, are covered too; {@link RuntimeServer} does. The guard needs
 * Helidon 4.5.4 or later, the first to end the connection after a {@code Connection: close}
 * response.
 *
 * <p>The {@code optionsFactory} is the seam through which deployments choose how a new session is
 * configured: it is given a {@link SessionRequest} of the generated session id and the {@link
 * Principal} that will own the session, so it can pick a workspace, model, budget or tool set per
 * tenant or subject, and produces a fully-populated {@link SessionOptions} for exactly that id;
 * options for any other id are refused, so a factory can never attach a request to another session.
 * A {@code 500} body carries a fixed message, never an exception's; a {@code 409} tells the owner
 * why its own session, closed or terminal, refused a message or interrupt.
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. Each request runs on its own virtual thread; {@link SessionRegistry} synchronises
 * shared session state.
 */
public final class AgentHttpService implements HttpService {

  private static final Logger LOGGER = Logger.getLogger(AgentHttpService.class.getName());

  private static final Map<String, String> NOT_FOUND = Map.of("error", "session not found");

  private static final Map<String, String> INTERNAL_ERROR =
      Map.of("error", "internal server error");

  private final SessionRegistry registry;
  private final SessionCreation creation;
  private final AuthenticationGate gate;
  private final ObjectMapper objectMapper;
  private final SteeringInput steering;
  private final SessionSlots eventStreams;
  private final SessionSlots resultPolls;

  /**
   * Build a service.
   *
   * @param registry registry of live sessions; non-null
   * @param optionsFactory function that maps a {@link SessionRequest}, the generated session id and
   *     the creating principal, to a fully-configured {@link SessionOptions} for that id; non-null
   * @param authenticator verifies every request's identity; non-null, with a valid {@link
   *     Authenticator#challenge()}
   * @param objectMapper mapper for request/response bodies; non-null. The service uses a copy that
   *     also writes {@link com.standardapplied.helios.core.tool.ToolOutcome}
   * @param eventsPathPrefix prefix used to build the {@code eventsUrl} returned by {@code POST
   *     /sessions} (e.g. {@code "/v1"}); non-null
   * @param limits bounds on each request; non-null
   * @throws NullPointerException if any argument, or the authenticator's challenge, is null
   * @throws IllegalArgumentException if the authenticator's challenge is not a valid RFC 9110
   *     challenge
   */
  public AgentHttpService(
      SessionRegistry registry,
      Function<SessionRequest, SessionOptions> optionsFactory,
      Authenticator authenticator,
      ObjectMapper objectMapper,
      String eventsPathPrefix,
      RequestLimits limits) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.creation =
        new SessionCreation(
            registry,
            Objects.requireNonNull(optionsFactory, "optionsFactory must not be null"),
            Objects.requireNonNull(eventsPathPrefix, "eventsPathPrefix must not be null"));
    this.gate = new AuthenticationGate(authenticator);
    this.objectMapper =
        ToolOutcomeSerializer.addTo(
            Objects.requireNonNull(objectMapper, "objectMapper must not be null"));
    Objects.requireNonNull(limits, "limits must not be null");
    this.steering = new SteeringInput(limits, this.objectMapper);
    this.eventStreams = SessionSlots.eventStreams(limits);
    this.resultPolls = SessionSlots.resultPolls(limits);
  }

  /**
   * What the service admitted and refused since it was built.
   *
   * @return a snapshot of the counters
   */
  public RuntimeCounters counters() {
    return new RuntimeCounters(
        creation.counts(), steering.counts(), eventStreams.counts(), resultPolls.counts());
  }

  @Override
  public void routing(HttpRules rules) {
    rules.post("/sessions", authenticated((caller, req, resp) -> creation.respond(caller, resp)));
    rules.post("/sessions/{sessionId}/messages", authenticated(owned(steering::send)));
    rules.post("/sessions/{sessionId}/interrupt", authenticated(owned(steering::interrupt)));
    rules.get(
        "/sessions/{sessionId}/events",
        authenticated(
            owned(
                limited(
                    eventStreams,
                    (session, caller, req, resp) ->
                        SessionEventStream.stream(session, resp, objectMapper, caller.ended())))));
    rules.get(
        "/sessions/{sessionId}/result",
        authenticated(
            owned(
                limited(
                    resultPolls,
                    (session, caller, req, resp) ->
                        ResultLongPoll.respond(session, req, resp, objectMapper, caller)))));
    rules.delete("/sessions/{sessionId}", authenticated(owned(this::delete)));
  }

  private Handler authenticated(CallerHandler handler) {
    return (req, resp) -> {
      UnreadBodyGuard.apply(req, resp);
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
   * send the exception's message to the client. Two failures stay Helidon's: an {@link
   * HttpException} Helidon raised carries its own status and message, and a response already under
   * way (an opened SSE sink, or a send the client disconnected from) can only be closed.
   */
  private static void answerFailure(ServerResponse resp, RuntimeException e) {
    if (resp.isSent() || e instanceof HttpException) {
      throw e;
    }
    LOGGER.log(Level.WARNING, "request failed", e);
    resp.status(Status.INTERNAL_SERVER_ERROR_500).send(INTERNAL_ERROR);
  }

  private CallerHandler owned(SessionHandler handler) {
    return (caller, req, resp) -> {
      var session = registry.find(req.path().pathParameters().get("sessionId"), caller.principal());
      if (session.isEmpty()) {
        resp.status(Status.NOT_FOUND_404).send(NOT_FOUND);
        return;
      }
      handler.handle(session.get(), caller, req, resp);
    };
  }

  /**
   * Answer the refusal without running {@code handler} when {@code slots} refuses the request;
   * otherwise hold its admission for as long as {@code handler} runs, even past the session's
   * removal.
   */
  private static SessionHandler limited(SessionSlots slots, SessionHandler handler) {
    return (session, caller, req, resp) -> {
      if (!slots.admit(session.sessionId(), caller.principal(), resp)) {
        return;
      }
      try {
        handler.handle(session, caller, req, resp);
      } finally {
        slots.release(session.sessionId(), caller.principal());
      }
    };
  }

  private void delete(AgentSession session, Caller caller, ServerRequest req, ServerResponse resp) {
    registry.close(session.sessionId(), caller.principal());
    resp.status(Status.NO_CONTENT_204).send();
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
