/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.runtime;

import com.standardapplied.helios.runtime.AuthenticationGate.Caller;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionOptions;
import io.helidon.http.HeaderValues;
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

/**
 * Helidon {@link HttpService} that exposes one {@link SessionRegistry}'s sessions over HTTP. The
 * routes mirror the spec §15.1 sketch and are mounted by the caller under whatever prefix fits the
 * deployment ({@code routing.register("/v1", new AgentHttpService(...))}). {@link RuntimeServer} is
 * the embedding that does this for you.
 *
 * <ul>
 *   <li>{@code POST /sessions} — create a fresh session; returns {@code {sessionId, eventsUrl}}
 *       with {@code 201 Created}.
 *   <li>{@code POST /sessions/{sessionId}/messages} — body {@code {text: "..."}}; queues the
 *       message and returns {@code 202 Accepted}; {@code 429} when the session's steering queue is
 *       full.
 *   <li>{@code POST /sessions/{sessionId}/interrupt} — body {@code {reason: "..."}}; queues a
 *       synthetic interrupt message and returns {@code 202 Accepted}.
 *   <li>{@code GET /sessions/{sessionId}/questions} — returns {@code 200 OK} with a JSON array of
 *       the session's {@linkplain AgentSession#pendingQuestions() pending questions}, each as its
 *       {@code QuestionAsked} event carried it: {@code questionId}, {@code header}, {@code
 *       question}, {@code options} ({@code label}, {@code description}) and {@code multiSelect}. A
 *       {@code questionId} starting {@code perm-} asks to approve a tool call; one starting {@code
 *       q-} is the model's own {@code AskUserQuestion}. The header does not tell them apart: the
 *       model chooses its own question's header and labels.
 *   <li>{@code POST /sessions/{sessionId}/questions/{questionId}} — body {@code {selectedLabels:
 *       ["Allow"], customText: "..."}}, {@code customText} optional; answers that question and
 *       returns {@code 202 Accepted}; {@code 404} when it is not pending, already answered or
 *       unknown. The answer names no tool or arguments, so it cannot widen what an approval covers.
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
 * Every request is admitted against finite bounds before its work starts, and a refused one is
 * answered at once, never queued. Once authenticated, a request takes one of its principal's {@link
 * RequestLimits#maxConcurrentRequestsPerPrincipal()} slots before any session lookup or body read,
 * and holds it until its handler returns: an event stream until it ends, a result poll until it is
 * answered, even if the session is deleted meanwhile. A request beyond the principal's slots is
 * {@code 429} {@code {"error":"too many requests in progress"}}. Open streams and waiting polls
 * share these slots with every other route, so size them above a principal's peak open streams.
 * {@code POST /sessions} then reserves capacity in the {@link SessionRegistry} before the {@code
 * optionsFactory} runs: a full cap of the caller's own is {@code 429}, the registry's {@code 503}.
 * A message, interrupt or answer body over {@link RequestLimits#maxRequestBytes()} is {@code 413};
 * one refused by a full steering queue is {@code 429}. Each of these {@code 429} and {@code 503}
 * answers has a fixed body and a {@code Retry-After} header. Across principals, bound the requests
 * in flight with Helidon's own {@code maxConcurrentRequests} on the {@link
 * io.helidon.webserver.WebServer}, as {@link RuntimeServer} does.
 *
 * <h2>JSON</h2>
 *
 * Helios writes its own JSON with one fixed mapper, always UTF-8. Mount the service on any Helidon
 * {@link io.helidon.webserver.WebServer}; no media support is needed.
 *
 * <h2>Unread bodies</h2>
 *
 * A Helios refusal answers without reading the body and closes the connection: an answer sent while
 * the request's body is not read to its end, such as a {@code 401}, {@code 404}, {@code 413},
 * {@code 429} or {@code 503} refused before the body, a fixed {@code 500}, or a {@code 201} for a
 * create that sent one, carries {@code Connection: close}, and Helidon (4.5.4 or later) writes it
 * whole and ends the connection, so nothing the client sends after it is read or run as a request.
 * Closing a connection whose body is unread makes the operating system reset it, discarding
 * response bytes not yet sent, so a large answer, or one whose packet is lost, can arrive cut
 * short; each of Helios's refusals fits in one packet.
 *
 * <p>Helidon's own answers on the same server, an unmatched path or an {@code Error} a route lets
 * escape, are Helidon's to frame. {@link RuntimeServer} sets Helidon's {@code
 * maxPayloadSize(maxRequestBytes)} and {@code Http1Config.continueImmediately(true)}, and so must a
 * {@link io.helidon.webserver.WebServer} of your own that mounts this service. Then no body byte is
 * ever parsed as a request, and Helidon's own answers read at most {@code maxRequestBytes} of a
 * body. Without them, a request sent with {@code Expect: 100-continue} to an unmatched path has its
 * body parsed as the next request.
 *
 * <p>The {@code optionsFactory} is the seam through which deployments choose how a new session is
 * configured: it is given a {@link SessionRequest} of the generated session id and the {@link
 * Principal} that will own the session, so it can pick a workspace, model, budget or tool set per
 * tenant or subject, and produces a fully-populated {@link SessionOptions} for exactly that id;
 * options for any other id are refused, so a factory can never attach a request to another session.
 * A {@code 500} body carries a fixed message, never an exception's; a {@code 409} tells the owner
 * why its own session, closed or terminal, refused a message, interrupt or answer.
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
  private final SteeringInput steering;
  private final OpenQuestions questions;
  private final KeyedSlots<Principal> inFlight;

  /**
   * Build a service.
   *
   * @param registry registry of live sessions; non-null
   * @param optionsFactory function that maps a {@link SessionRequest}, the generated session id and
   *     the creating principal, to a fully-configured {@link SessionOptions} for that id; non-null
   * @param authenticator verifies every request's identity; non-null, with a valid {@link
   *     Authenticator#challenge()}
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
      String eventsPathPrefix,
      RequestLimits limits) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.creation =
        new SessionCreation(
            registry,
            Objects.requireNonNull(optionsFactory, "optionsFactory must not be null"),
            Objects.requireNonNull(eventsPathPrefix, "eventsPathPrefix must not be null"));
    this.gate = new AuthenticationGate(authenticator);
    Objects.requireNonNull(limits, "limits must not be null");
    this.steering = new SteeringInput(limits.maxRequestBytes(), JsonBody.MAPPER);
    this.questions = new OpenQuestions(limits.maxRequestBytes());
    this.inFlight = new KeyedSlots<>(limits.maxConcurrentRequestsPerPrincipal());
  }

  @Override
  public void routing(HttpRules rules) {
    rules.post("/sessions", authenticated((caller, req, resp) -> creation.respond(caller, resp)));
    rules.post("/sessions/{sessionId}/messages", authenticated(owned(steering::send)));
    rules.post("/sessions/{sessionId}/interrupt", authenticated(owned(steering::interrupt)));
    rules.get("/sessions/{sessionId}/questions", authenticated(owned(questions::list)));
    rules.post(
        "/sessions/{sessionId}/questions/{questionId}", authenticated(owned(questions::answer)));
    rules.get(
        "/sessions/{sessionId}/events",
        authenticated(
            owned(
                (session, caller, req, resp) ->
                    SessionEventStream.stream(session, resp, JsonBody.MAPPER, caller.ended()))));
    rules.get(
        "/sessions/{sessionId}/result",
        authenticated(
            owned(
                (session, caller, req, resp) ->
                    ResultLongPoll.respond(session, req, resp, JsonBody.MAPPER, caller))));
    rules.delete("/sessions/{sessionId}", authenticated(owned(this::delete)));
  }

  /**
   * Run {@code handler} for an authenticated caller while it holds one of its principal's request
   * slots, through any failure answer, even past its session's removal; answer {@code 429} without
   * running it when the principal has none free. Every answer that leaves the body unread closes
   * the connection.
   */
  private Handler authenticated(CallerHandler handler) {
    return (req, resp) -> {
      closeIfUnread(req, resp);
      var caller = gate.admit(req, resp);
      if (caller.isEmpty()) {
        return;
      }
      var principal = caller.get().principal();
      var admitted = false;
      try {
        admitted = inFlight.tryAcquire(principal);
        if (admitted) {
          handler.handle(caller.get(), req, resp);
        } else {
          Overload.reject(resp, Status.TOO_MANY_REQUESTS_429, Overload.TOO_MANY_REQUESTS);
        }
      } catch (RuntimeException e) {
        answerFailure(resp, e);
      } finally {
        if (admitted) {
          inFlight.release(principal);
        }
        caller.get().release();
      }
    };
  }

  /** End the connection after {@code resp} if, when it is sent, {@code req}'s body is unread. */
  private static void closeIfUnread(ServerRequest req, ServerResponse resp) {
    resp.beforeSend(
        () -> {
          if (!req.content().consumed()) {
            resp.header(HeaderValues.CONNECTION_CLOSE);
          }
        });
  }

  /**
   * Answer a route that failed with a fixed {@code 500} and log the failure here, whatever error
   * handling the deployer's routing has, which may quote the exception's message (Helidon's {@code
   * include-entity}). Two failures stay Helidon's: an {@link HttpException} Helidon raised carries
   * its own status and message, and a response already under way (an opened SSE sink, or a send the
   * client disconnected from) can only be closed.
   */
  private static void answerFailure(ServerResponse resp, RuntimeException e) {
    if (resp.isSent() || e instanceof HttpException) {
      throw e;
    }
    LOGGER.log(Level.WARNING, "request failed", e);
    JsonBody.send(resp, Status.INTERNAL_SERVER_ERROR_500, INTERNAL_ERROR);
  }

  private CallerHandler owned(SessionHandler handler) {
    return (caller, req, resp) -> {
      var session = registry.find(req.path().pathParameters().get("sessionId"), caller.principal());
      if (session.isEmpty()) {
        JsonBody.send(resp, Status.NOT_FOUND_404, NOT_FOUND);
        return;
      }
      handler.handle(session.get(), caller, req, resp);
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
