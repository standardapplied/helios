/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.runtime;

import ai.singlr.core.common.Ids;
import ai.singlr.core.common.Strings;
import ai.singlr.session.AgentSession;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.ResultMessage;
import ai.singlr.session.SessionOptions;
import ai.singlr.session.UserMessage;
import io.helidon.http.Status;
import io.helidon.http.sse.SseEvent;
import io.helidon.webserver.CloseConnectionException;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.helidon.webserver.sse.SseSink;
import java.io.IOException;
import java.net.SocketException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import tools.jackson.databind.ObjectMapper;

/**
 * Helidon {@link HttpService} that exposes one {@link SessionRegistry}'s sessions over HTTP. The
 * five Phase 1 routes mirror the spec §15.1 sketch and are mounted by the caller under whatever
 * prefix fits the deployment ({@code routing.register("/v1", new AgentHttpService(...))}).
 *
 * <ul>
 *   <li>{@code POST /sessions} — create a fresh session; returns {@code {sessionId, eventsUrl}}
 *       with {@code 201 Created}.
 *   <li>{@code POST /sessions/{sessionId}/messages} — body {@code {text: "..."}}; queues the
 *       message and returns {@code 202 Accepted}.
 *   <li>{@code POST /sessions/{sessionId}/interrupt} — body {@code {reason: "..."}}; queues a
 *       synthetic interrupt message and returns {@code 202 Accepted}.
 *   <li>{@code GET /sessions/{sessionId}/events} — opens an SSE stream of {@link QueryEvent}s. The
 *       handler blocks the request thread until the publisher signals {@code onComplete} or the
 *       client disconnects.
 *   <li>{@code GET /sessions/{sessionId}/result?timeout=<seconds>} — long-poll for the terminal
 *       {@link ai.singlr.session.ResultMessage ResultMessage}. Returns {@code 200 OK} with body
 *       {@code {type: "<SubtypeName>", result: <record-fields>}} when terminal; {@code 204 No
 *       Content} when the {@code timeout} elapses with no terminal. {@code timeout} defaults to 60
 *       s and is clamped to {@code [0, 300]} so a single request cannot pin a server thread longer
 *       than five minutes.
 *   <li>{@code DELETE /sessions/{sessionId}} — closes and unregisters the session; returns {@code
 *       204 No Content}.
 * </ul>
 *
 * <p>The {@code optionsFactory} is the seam through which deployments choose how a new session is
 * configured: the runtime takes the generated session id and produces a fully-populated {@link
 * SessionOptions}. For Phase 1 the typical impl returns the same {@link ai.singlr.core.model.Model
 * Model} for every session.
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. Each request runs on its own virtual thread; {@link SessionRegistry} synchronises
 * shared session state.
 */
public final class AgentHttpService implements HttpService {

  private static final Logger LOGGER = Logger.getLogger(AgentHttpService.class.getName());

  private final SessionRegistry registry;
  private final Function<String, SessionOptions> optionsFactory;
  private final ObjectMapper objectMapper;
  private final String eventsPathPrefix;

  /**
   * Build a service.
   *
   * @param registry registry of live sessions; non-null
   * @param optionsFactory function that maps a generated session id to a fully-configured {@link
   *     SessionOptions}; non-null
   * @param objectMapper mapper for request/response bodies; non-null
   * @param eventsPathPrefix prefix used to build the {@code eventsUrl} returned by {@code POST
   *     /sessions} (e.g. {@code "/v1"}); non-null
   * @throws NullPointerException if any argument is null
   */
  public AgentHttpService(
      SessionRegistry registry,
      Function<String, SessionOptions> optionsFactory,
      ObjectMapper objectMapper,
      String eventsPathPrefix) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.optionsFactory = Objects.requireNonNull(optionsFactory, "optionsFactory must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    this.eventsPathPrefix =
        Objects.requireNonNull(eventsPathPrefix, "eventsPathPrefix must not be null");
  }

  @Override
  public void routing(HttpRules rules) {
    rules.post("/sessions", this::createHandler);
    rules.post("/sessions/{sessionId}/messages", this::messageHandler);
    rules.post("/sessions/{sessionId}/interrupt", this::interruptHandler);
    rules.get("/sessions/{sessionId}/events", this::eventsHandler);
    rules.get("/sessions/{sessionId}/result", this::resultHandler);
    rules.delete("/sessions/{sessionId}", this::deleteHandler);
  }

  // ── handlers ────────────────────────────────────────────────────────────

  private void createHandler(ServerRequest req, ServerResponse resp) {
    var sessionId = "sess-" + Ids.newId();
    SessionOptions options;
    try {
      options = optionsFactory.apply(sessionId);
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "optionsFactory failed for session " + sessionId, e);
      resp.status(Status.INTERNAL_SERVER_ERROR_500)
          .send(Map.of("error", "session options factory failed: " + e.getMessage()));
      return;
    }
    if (!options.sessionId().equals(sessionId)) {
      LOGGER.warning(
          "optionsFactory ignored the supplied sessionId; using factory-provided id "
              + options.sessionId());
    }
    registry.create(options);
    resp.status(Status.CREATED_201)
        .send(
            Map.of(
                "sessionId",
                options.sessionId(),
                "eventsUrl",
                eventsPathPrefix + "/sessions/" + options.sessionId() + "/events"));
  }

  private void messageHandler(ServerRequest req, ServerResponse resp) {
    var sessionOpt = findSession(req, resp);
    if (sessionOpt.isEmpty()) {
      return;
    }
    Map<String, Object> body;
    try {
      body = readJsonBody(req);
    } catch (JacksonRuntimeException e) {
      resp.status(Status.BAD_REQUEST_400).send(Map.of("error", "invalid JSON body"));
      return;
    }
    var text = body.get("text");
    if (!(text instanceof String s) || Strings.isBlank(s)) {
      resp.status(Status.BAD_REQUEST_400)
          .send(Map.of("error", "'text' field must be a non-blank string"));
      return;
    }
    try {
      sessionOpt.get().send(UserMessage.text(s));
    } catch (IllegalStateException e) {
      resp.status(Status.CONFLICT_409).send(Map.of("error", e.getMessage()));
      return;
    }
    resp.status(Status.ACCEPTED_202).send();
  }

  private void interruptHandler(ServerRequest req, ServerResponse resp) {
    var sessionOpt = findSession(req, resp);
    if (sessionOpt.isEmpty()) {
      return;
    }
    Map<String, Object> body;
    try {
      body = readJsonBody(req);
    } catch (JacksonRuntimeException e) {
      resp.status(Status.BAD_REQUEST_400).send(Map.of("error", "invalid JSON body"));
      return;
    }
    var reason = body.get("reason");
    if (!(reason instanceof String r) || Strings.isBlank(r)) {
      resp.status(Status.BAD_REQUEST_400)
          .send(Map.of("error", "'reason' field must be a non-blank string"));
      return;
    }
    try {
      sessionOpt.get().interrupt(r);
    } catch (IllegalStateException e) {
      resp.status(Status.CONFLICT_409).send(Map.of("error", e.getMessage()));
      return;
    }
    resp.status(Status.ACCEPTED_202).send();
  }

  private void eventsHandler(ServerRequest req, ServerResponse resp) {
    var sessionOpt = findSession(req, resp);
    if (sessionOpt.isEmpty()) {
      return;
    }
    var session = sessionOpt.get();
    var sink = resp.sink(SseSink.TYPE);
    var done = new CountDownLatch(1);
    var subscription = new AtomicReference<Flow.Subscription>();
    session
        .events()
        .subscribe(
            new Flow.Subscriber<QueryEvent>() {
              @Override
              public void onSubscribe(Flow.Subscription s) {
                subscription.set(s);
                s.request(Long.MAX_VALUE);
                // Synthetic Ready event so clients can synchronously confirm subscription is
                // live before triggering work — closes the race between Helidon writing 200 OK
                // and the subscriber actually registering on the SubmissionPublisher.
                try {
                  sink.emit(SseEvent.builder().name("Ready").data("{}").build());
                } catch (Exception ignored) {
                  // sink failures are handled in onNext below
                }
              }

              @Override
              public void onNext(QueryEvent event) {
                try {
                  sink.emit(
                      SseEvent.builder()
                          .name(eventName(event))
                          .data(objectMapper.writeValueAsString(redactForWire(event)))
                          .build());
                } catch (Exception ex) {
                  if (!isDisconnect(ex)) {
                    LOGGER.log(
                        Level.WARNING, "SSE emit failed for session " + session.sessionId(), ex);
                  }
                  var s = subscription.get();
                  if (s != null) {
                    s.cancel();
                  }
                  done.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {
                LOGGER.log(
                    Level.WARNING,
                    "events publisher errored for session " + session.sessionId(),
                    t);
                done.countDown();
              }

              @Override
              public void onComplete() {
                done.countDown();
              }
            });
    try {
      done.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      try {
        sink.close();
      } catch (Exception ignored) {
        // sink may already be closed by Helidon if the client disconnected
      }
    }
  }

  private void resultHandler(ServerRequest req, ServerResponse resp) {
    var sessionOpt = findSession(req, resp);
    if (sessionOpt.isEmpty()) {
      return;
    }
    var session = sessionOpt.orElseThrow();
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
  record ResultLongPollOutcome(Status status, Object body) {}

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
  static ResultLongPollOutcome awaitResult(
      CompletableFuture<ResultMessage> future, long timeoutSeconds, String sessionIdForLog) {
    try {
      var terminal = future.get(timeoutSeconds, TimeUnit.SECONDS).withoutStackTraces();
      return new ResultLongPollOutcome(
          Status.OK_200, Map.of("type", terminal.getClass().getSimpleName(), "result", terminal));
    } catch (TimeoutException e) {
      return new ResultLongPollOutcome(Status.NO_CONTENT_204, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ResultLongPollOutcome(
          Status.SERVICE_UNAVAILABLE_503,
          Map.of("error", "request interrupted while waiting for session result"));
    } catch (ExecutionException e) {
      // Full cause logged server-side. The HTTP body must NOT echo cause.getMessage() — it may
      // contain internal paths, secret fragments, or class names that should not exfiltrate.
      LOGGER.log(
          Level.WARNING, "session " + sessionIdForLog + " result future failed exceptionally", e);
      return new ResultLongPollOutcome(
          Status.INTERNAL_SERVER_ERROR_500, Map.of("error", "session terminated abnormally"));
    }
  }

  /** Default long-poll timeout when the client omits {@code ?timeout}. */
  static final long DEFAULT_RESULT_TIMEOUT_SECONDS = 60L;

  /** Hard cap on the long-poll timeout so one request cannot pin a server thread indefinitely. */
  static final long MAX_RESULT_TIMEOUT_SECONDS = 300L;

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

  private void deleteHandler(ServerRequest req, ServerResponse resp) {
    var sessionId = req.path().pathParameters().get("sessionId");
    var removed = registry.close(sessionId);
    if (!removed) {
      resp.status(Status.NOT_FOUND_404).send(Map.of("error", "session not found"));
      return;
    }
    resp.status(Status.NO_CONTENT_204).send();
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private Optional<AgentSession> findSession(ServerRequest req, ServerResponse resp) {
    var sessionId = req.path().pathParameters().get("sessionId");
    var sessionOpt = registry.get(sessionId);
    if (sessionOpt.isEmpty()) {
      resp.status(Status.NOT_FOUND_404).send(Map.of("error", "session not found"));
    }
    return sessionOpt;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readJsonBody(ServerRequest req) {
    try {
      return (Map<String, Object>) req.content().as(Map.class);
    } catch (RuntimeException e) {
      throw new JacksonRuntimeException("failed to parse request body", e);
    }
  }

  private static String eventName(QueryEvent event) {
    return event.getClass().getSimpleName();
  }

  /**
   * Strip stack-trace frames from any error payload carried inside {@code event} before
   * wire-serialisation. Used for SSE emit so terminal events ({@link QueryEvent.LoopEnded} carrying
   * an {@link ResultMessage.ErrorDuringExecution}, or a standalone {@link QueryEvent.Error}) do not
   * leak library-internal class names and file:line numbers to clients. Returns {@code event}
   * unchanged when there is nothing to redact. Package-private for direct unit-test access.
   */
  static QueryEvent redactForWire(QueryEvent event) {
    return switch (event) {
      case QueryEvent.LoopEnded le -> {
        var redacted = le.result().withoutStackTraces();
        yield redacted == le.result()
            ? le
            : new QueryEvent.LoopEnded(le.sessionId(), le.turnIndex(), le.timestamp(), redacted);
      }
      case QueryEvent.Error err -> {
        var redacted = err.error().withoutStackTrace();
        yield redacted == err.error()
            ? err
            : new QueryEvent.Error(err.sessionId(), err.turnIndex(), err.timestamp(), redacted);
      }
      default -> event;
    };
  }

  /**
   * Decide whether {@code ex} (or any of its causes) represents a client disconnect during SSE
   * emit. The agent loop should keep producing events for in-flight work even when the HTTP peer
   * has gone away; the only effect is that we stop forwarding to the dead sink.
   *
   * <p>Helidon 4.x raises {@link CloseConnectionException} (and its subclass {@code
   * ServerConnectionException}) when it detects the peer closed the socket — that is the
   * authoritative typed signal and the first check below.
   *
   * <p>String-matching on {@link SocketException} / {@link IOException} messages is preserved as a
   * fallback for code paths that bypass Helidon's wrapping (raw socket I/O surfacing through the
   * JDK), and for forward-compatibility if a future Helidon version stops wrapping in some
   * scenarios. Matches the three messages the JDK socket layer produces on local-peer hangups
   * across platforms.
   */
  static boolean isDisconnect(Throwable ex) {
    var current = ex;
    while (current != null) {
      if (current instanceof CloseConnectionException) {
        return true;
      }
      if (current instanceof SocketException || current instanceof IOException) {
        var msg = current.getMessage();
        if (msg != null
            && (msg.contains("Broken pipe")
                || msg.contains("Connection reset")
                || msg.contains("Socket closed"))) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
  }
}
