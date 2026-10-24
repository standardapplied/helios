/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.runtime.AuthenticationGate.Caller;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.ResultMessage;
import io.helidon.http.HeaderValues;
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
import tools.jackson.databind.ObjectMapper;

/**
 * The long-poll behind {@code GET /sessions/{sessionId}/result?timeout=<seconds>}: waits up to the
 * clamped timeout for the session's terminal {@link ResultMessage} and answers {@code 200} with
 * {@code {type, result}}, {@code 204} when the wait ends first, {@code 401} when the caller's
 * identity expires or is revoked first, or an error status when the wait is interrupted or the
 * session failed. Once the identity has ended the result is never disclosed, even if it is ready:
 * the body is serialized before the response is committed and the identity is checked again
 * immediately before it is sent.
 */
final class ResultLongPoll {

  /** The HTTP surface's logger, so one logging configuration covers every route. */
  private static final Logger LOGGER = Logger.getLogger(AgentHttpService.class.getName());

  /** Default long-poll timeout when the client omits {@code ?timeout}. */
  static final long DEFAULT_RESULT_TIMEOUT_SECONDS = 60L;

  /** Hard cap on the long-poll timeout so one request cannot pin a server thread indefinitely. */
  static final long MAX_RESULT_TIMEOUT_SECONDS = 300L;

  private static final Map<String, String> INTERRUPTED =
      Map.of("error", "request interrupted while waiting for session result");

  private static final Map<String, String> ABNORMAL =
      Map.of("error", "session terminated abnormally");

  private ResultLongPoll() {}

  /**
   * Answer the long-poll for {@code session}'s terminal result. The wait also watches a future of
   * its own, {@code answered}, completed once the wait is over: completing it unhooks the {@code
   * anyOf} relay from the session's and the identity's futures, so a timed-out poll leaves nothing
   * watching either.
   *
   * @param session the session whose result is awaited; non-null
   * @param req the request carrying the optional {@code timeout} query parameter; non-null
   * @param resp the response the outcome is sent on; non-null
   * @param mapper serializes the result's body; non-null
   * @param caller the admitted caller, whose identity may end while the poll waits; non-null
   */
  static void respond(
      AgentSession session,
      ServerRequest req,
      ServerResponse resp,
      ObjectMapper mapper,
      Caller caller) {
    var timeoutSeconds = parseResultTimeoutSeconds(req.query().first("timeout").orElse(null));
    var result = session.result();
    var answered = new CompletableFuture<Void>();
    var timedOut = false;
    var interrupted = false;
    try {
      CompletableFuture.anyOf(result, caller.ended(), answered)
          .get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      timedOut = true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      interrupted = true;
    } catch (ExecutionException ignored) {
    } finally {
      answered.complete(null);
    }
    if (caller.answeredAsEnded(resp)) {
      return;
    }
    if (timedOut) {
      resp.status(Status.NO_CONTENT_204).send();
      return;
    }
    if (interrupted) {
      JsonBody.send(resp, Status.SERVICE_UNAVAILABLE_503, INTERRUPTED);
      return;
    }
    ResultMessage terminal;
    try {
      terminal = result.join();
    } catch (CompletionException | CancellationException e) {
      LOGGER.log(
          Level.WARNING,
          "session " + session.sessionId() + " result future failed exceptionally",
          e);
      JsonBody.send(resp, Status.INTERNAL_SERVER_ERROR_500, ABNORMAL);
      return;
    }
    var body = new LinkedHashMap<String, Object>();
    body.put("type", terminal.getClass().getSimpleName());
    body.put("result", terminal);
    var json = mapper.writeValueAsBytes(body);
    if (caller.answeredAsEnded(resp)) {
      return;
    }
    resp.header(HeaderValues.CONTENT_TYPE_JSON);
    resp.status(Status.OK_200).send(json);
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
