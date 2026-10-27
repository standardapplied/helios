/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.runtime.AuthenticationGate.Caller;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.SessionUnavailableException;
import io.helidon.http.Status;
import io.helidon.webserver.http.ServerResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The route behind {@code POST /sessions}: reserves session capacity for the caller in the {@link
 * SessionRegistry} before anything else, asks the options factory for the options of a freshly
 * generated session id owned by the caller ({@link SessionRequest}), and registers the session. A
 * refused reservation is answered {@code 429} or {@code 503} before the factory runs; a factory
 * that fails, or returns options for another id, is answered {@code 500}; an execution provider
 * that does not accept the session ({@link SessionUnavailableException}), {@code 503} with a fixed
 * body, its redacted reason logged; an identity that ended meanwhile, {@code 401}. On every answer
 * but {@code 201} the reservation is given back.
 */
final class SessionCreation {

  /** The HTTP surface's logger, so one logging configuration covers every route. */
  private static final Logger LOGGER = Logger.getLogger(AgentHttpService.class.getName());

  private static final Map<String, String> FACTORY_FAILED =
      Map.of("error", "session options factory failed");

  private static final Map<String, String> UNAVAILABLE = Map.of("error", "session unavailable");

  private final SessionRegistry registry;
  private final Function<SessionRequest, SessionOptions> optionsFactory;
  private final String eventsPathPrefix;

  SessionCreation(
      SessionRegistry registry,
      Function<SessionRequest, SessionOptions> optionsFactory,
      String eventsPathPrefix) {
    this.registry = registry;
    this.optionsFactory = optionsFactory;
    this.eventsPathPrefix = eventsPathPrefix;
  }

  /** Create a session for {@code caller}, or answer why not. */
  void respond(Caller caller, ServerResponse resp) {
    SessionReservation reservation;
    try {
      reservation = registry.reserve(caller.principal());
    } catch (SessionCapacityException e) {
      Overload.reject(resp, e);
      return;
    }
    try {
      register(reservation, caller, resp);
    } finally {
      reservation.close();
    }
  }

  private void register(SessionReservation reservation, Caller caller, ServerResponse resp) {
    var sessionId = "sess-" + Ids.newId();
    var options = options(new SessionRequest(sessionId, caller.principal()));
    if (options.isEmpty()) {
      JsonBody.send(resp, Status.INTERNAL_SERVER_ERROR_500, FACTORY_FAILED);
      return;
    }
    if (caller.answeredAsEnded(resp)) {
      return;
    }
    try {
      reservation.register(options.get());
    } catch (SessionUnavailableException e) {
      LOGGER.log(Level.WARNING, "session " + sessionId + " unavailable: " + e.getMessage());
      JsonBody.send(resp, Status.SERVICE_UNAVAILABLE_503, UNAVAILABLE);
      return;
    }
    var created = new LinkedHashMap<String, String>();
    created.put("sessionId", sessionId);
    created.put("eventsUrl", eventsPathPrefix + "/sessions/" + sessionId + "/events");
    JsonBody.send(resp, Status.CREATED_201, created);
  }

  /** The options factory's options for {@code request}; empty, and logged, if it failed. */
  private Optional<SessionOptions> options(SessionRequest request) {
    var sessionId = request.sessionId();
    SessionOptions options;
    try {
      options = optionsFactory.apply(request);
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "optionsFactory failed for session " + sessionId, e);
      return Optional.empty();
    }
    if (options == null || !options.sessionId().equals(sessionId)) {
      LOGGER.warning("optionsFactory did not return options for session " + sessionId);
      return Optional.empty();
    }
    return Optional.of(options);
  }
}
