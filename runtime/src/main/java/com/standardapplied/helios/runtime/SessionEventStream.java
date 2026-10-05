/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import io.helidon.http.sse.SseEvent;
import io.helidon.webserver.CloseConnectionException;
import io.helidon.webserver.http.ServerResponse;
import io.helidon.webserver.sse.SseSink;
import java.io.IOException;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import tools.jackson.databind.ObjectMapper;

/**
 * The SSE stream behind {@code GET /sessions/{sessionId}/events}: subscribes to one session's
 * {@link QueryEvent}s, emits a synthetic {@code Ready} event once subscribed and then each event
 * named by its subtype, and holds the request thread until the publisher completes or errors or the
 * client disconnects.
 */
final class SessionEventStream implements Flow.Subscriber<QueryEvent> {

  /** The HTTP surface's logger, so one logging configuration covers every route. */
  private static final Logger LOGGER = Logger.getLogger(AgentHttpService.class.getName());

  private final String sessionId;
  private final SseSink sink;
  private final ObjectMapper objectMapper;
  private final CountDownLatch done = new CountDownLatch(1);
  private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

  SessionEventStream(String sessionId, SseSink sink, ObjectMapper objectMapper) {
    this.sessionId = sessionId;
    this.sink = sink;
    this.objectMapper = objectMapper;
  }

  /**
   * Stream {@code session}'s events to {@code resp} until the stream ends, then close the sink.
   *
   * @param session the session whose events are streamed; non-null
   * @param resp the response the SSE sink is opened on; non-null
   * @param objectMapper serializes each event as the SSE data; non-null
   */
  static void stream(AgentSession session, ServerResponse resp, ObjectMapper objectMapper) {
    var stream = new SessionEventStream(session.sessionId(), resp.sink(SseSink.TYPE), objectMapper);
    session.events().subscribe(stream);
    stream.awaitEndThenClose();
  }

  /** Wait until the stream ends, then close the sink. Package-private for tests. */
  void awaitEndThenClose() {
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
              .data(objectMapper.writeValueAsString(event))
              .build());
    } catch (Exception ex) {
      if (!isDisconnect(ex)) {
        LOGGER.log(Level.WARNING, "SSE emit failed for session " + sessionId, ex);
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
    LOGGER.log(Level.WARNING, "events publisher errored for session " + sessionId, t);
    done.countDown();
  }

  @Override
  public void onComplete() {
    done.countDown();
  }

  private static String eventName(QueryEvent event) {
    return event.getClass().getSimpleName();
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
