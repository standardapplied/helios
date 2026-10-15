/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.ResultMessage;
import io.helidon.http.Status;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The long-poll behind {@code GET /sessions/{sessionId}/result?timeout=<seconds>}: waits up to the
 * clamped timeout for the session's terminal {@link ResultMessage} and answers {@code 200} with
 * {@code {type, result}}, {@code 204} when the wait ends first, {@code 401} when the caller's
 * identity expires or is revoked first, or an error status when the wait is interrupted or the
 * session failed. Once the identity has ended the result is never disclosed, even if it is ready.
 */
final class ResultLongPoll {

  /** The HTTP surface's logger, so one logging configuration covers every route. */
  private static final Logger LOGGER = Logger.getLogger(AgentHttpService.class.getName());

  /** Default long-poll timeout when the client omits {@code ?timeout}. */
  static final long DEFAULT_RESULT_TIMEOUT_SECONDS = 60L;

  /** Hard cap on the long-poll timeout so one request cannot pin a server thread indefinitely. */
  static final long MAX_RESULT_TIMEOUT_SECONDS = 300L;

  private static final Outcome IDENTITY_ENDED =
      new Outcome(Status.UNAUTHORIZED_401, AuthenticationGate.UNAUTHENTICATED);

  private ResultLongPoll() {}

  /**
   * Answer the long-poll for {@code session}'s terminal result.
   *
   * @param session the session whose result is awaited; non-null
   * @param req the request carrying the optional {@code timeout} query parameter; non-null
   * @param resp the response the outcome is sent on; non-null
   * @param identityEnded completes when the caller's identity expires or is revoked; non-null
   */
  static void respond(
      AgentSession session,
      ServerRequest req,
      ServerResponse resp,
      CompletableFuture<Void> identityEnded) {
    var timeoutSeconds = parseResultTimeoutSeconds(req.query().first("timeout").orElse(null));
    var outcome = awaitResult(session.result(), identityEnded, timeoutSeconds, session.sessionId());
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
   * @param identityEnded completes when the caller's identity ends; non-null
   * @param timeoutSeconds non-negative wait budget
   * @param sessionIdForLog session id used only for the WARNING log on execution failure
   * @return outcome to translate to the HTTP response
   */
  static Outcome awaitResult(
      CompletableFuture<ResultMessage> future,
      CompletableFuture<Void> identityEnded,
      long timeoutSeconds,
      String sessionIdForLog) {
    var answered = new CompletableFuture<Void>();
    var first = CompletableFuture.anyOf(future, identityEnded, answered);
    try {
      first.get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      return new Outcome(Status.NO_CONTENT_204, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Outcome(
          Status.SERVICE_UNAVAILABLE_503,
          Map.of("error", "request interrupted while waiting for session result"));
    } catch (ExecutionException e) {
      return settled(future, identityEnded, sessionIdForLog);
    } finally {
      answered.complete(null);
    }
    return settled(future, identityEnded, sessionIdForLog);
  }

  private static Outcome settled(
      CompletableFuture<ResultMessage> future,
      CompletableFuture<Void> identityEnded,
      String sessionIdForLog) {
    if (identityEnded.isDone()) {
      return IDENTITY_ENDED;
    }
    try {
      return success(future.join());
    } catch (CompletionException | CancellationException e) {
      LOGGER.log(
          Level.WARNING, "session " + sessionIdForLog + " result future failed exceptionally", e);
      return new Outcome(
          Status.INTERNAL_SERVER_ERROR_500, Map.of("error", "session terminated abnormally"));
    }
  }

  private static Outcome success(ResultMessage terminal) {
    var body = new LinkedHashMap<String, Object>();
    body.put("type", terminal.getClass().getSimpleName());
    body.put("result", terminal);
    return new Outcome(Status.OK_200, body);
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
