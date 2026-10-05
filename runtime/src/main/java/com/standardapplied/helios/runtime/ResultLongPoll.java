/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.ResultMessage;
import io.helidon.http.Status;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The long-poll behind {@code GET /sessions/{sessionId}/result?timeout=<seconds>}: waits up to the
 * clamped timeout for the session's terminal {@link ResultMessage} and answers {@code 200} with
 * {@code {type, result}}, {@code 204} when the wait ends first, or an error status when the wait is
 * interrupted or the session failed.
 */
final class ResultLongPoll {

  /** The HTTP surface's logger, so one logging configuration covers every route. */
  private static final Logger LOGGER = Logger.getLogger(AgentHttpService.class.getName());

  /** Default long-poll timeout when the client omits {@code ?timeout}. */
  static final long DEFAULT_RESULT_TIMEOUT_SECONDS = 60L;

  /** Hard cap on the long-poll timeout so one request cannot pin a server thread indefinitely. */
  static final long MAX_RESULT_TIMEOUT_SECONDS = 300L;

  private ResultLongPoll() {}

  /**
   * Answer the long-poll for {@code session}'s terminal result.
   *
   * @param session the session whose result is awaited; non-null
   * @param req the request carrying the optional {@code timeout} query parameter; non-null
   * @param resp the response the outcome is sent on; non-null
   */
  static void respond(AgentSession session, ServerRequest req, ServerResponse resp) {
    var timeoutSeconds = parseResultTimeoutSeconds(req.query().first("timeout").orElse(null));
    var outcome = awaitResult(session.result(), timeoutSeconds, session.sessionId());
    if (outcome.body() == null) {
      resp.status(outcome.status()).send();
    } else {
      resp.status(outcome.status()).send(outcome.body());
    }
  }

  /**
   * Outcome of a long-poll wait on a session's terminal future, captured as an HTTP {@link Status}
   * + optional body. {@code null} body produces a body-less response (used for the {@code 204 No
   * Content} timeout case).
   */
  record Outcome(Status status, Object body) {}

  /**
   * Wait up to {@code timeoutSeconds} for {@code future} to complete and translate the result into
   * an HTTP status + body. Package-private so unit tests can exercise the catch paths ({@link
   * InterruptedException} / {@link ExecutionException}) that are awkward to reach from a black-box
   * HTTP test.
   *
   * @param future the session's result future; non-null
   * @param timeoutSeconds non-negative wait budget
   * @param sessionIdForLog session id used only for the WARNING log on execution failure
   * @return outcome to translate to the HTTP response
   */
  static Outcome awaitResult(
      CompletableFuture<ResultMessage> future, long timeoutSeconds, String sessionIdForLog) {
    try {
      var terminal = future.get(timeoutSeconds, TimeUnit.SECONDS);
      return new Outcome(
          Status.OK_200, Map.of("type", terminal.getClass().getSimpleName(), "result", terminal));
    } catch (TimeoutException e) {
      return new Outcome(Status.NO_CONTENT_204, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Outcome(
          Status.SERVICE_UNAVAILABLE_503,
          Map.of("error", "request interrupted while waiting for session result"));
    } catch (ExecutionException e) {
      // Helios is a library; diagnostic info reaches the deployer, who decides whether to
      // forward it to their downstream HTTP clients. The cause message is part of that
      // diagnostic surface — stripping it here would force deployers to dig through server-side
      // logs to correlate a failed request with what actually went wrong. Deployers that need to
      // sanitise the body can wrap this response at their HTTP layer.
      LOGGER.log(
          Level.WARNING, "session " + sessionIdForLog + " result future failed exceptionally", e);
      var cause = e.getCause();
      var msg = cause.getMessage() == null ? "unknown" : cause.getMessage();
      return new Outcome(
          Status.INTERNAL_SERVER_ERROR_500,
          Map.of("error", "session terminated abnormally: " + msg));
    }
  }

  /**
   * Parse the {@code timeout} query parameter. {@code null} or blank → {@link
   * #DEFAULT_RESULT_TIMEOUT_SECONDS}; malformed values silently fall back to the default rather
   * than 400ing (long-poll clients sometimes omit or mistype the param). Negative values clamp to
   * {@code 0}; values above {@link #MAX_RESULT_TIMEOUT_SECONDS} clamp to the cap.
   *
   * <p>Package-private for unit-test access; the HTTP handler reads the query param and passes the
   * raw value here.
   *
   * @param raw the raw query-string value; may be {@code null}
   * @return a long in {@code [0, MAX_RESULT_TIMEOUT_SECONDS]}
   */
  static long parseResultTimeoutSeconds(String raw) {
    if (Strings.isBlank(raw)) {
      return DEFAULT_RESULT_TIMEOUT_SECONDS;
    }
    long n;
    try {
      n = Long.parseLong(raw.trim());
    } catch (NumberFormatException e) {
      return DEFAULT_RESULT_TIMEOUT_SECONDS;
    }
    if (n < 0L) {
      return 0L;
    }
    if (n > MAX_RESULT_TIMEOUT_SECONDS) {
      return MAX_RESULT_TIMEOUT_SECONDS;
    }
    return n;
  }
}
