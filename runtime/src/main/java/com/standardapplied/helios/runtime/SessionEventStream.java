/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.EventStream;
import com.standardapplied.helios.session.QueryEvent;
import io.helidon.http.sse.SseEvent;
import io.helidon.webserver.CloseConnectionException;
import io.helidon.webserver.http.ServerResponse;
import io.helidon.webserver.sse.SseSink;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import tools.jackson.databind.ObjectMapper;

/**
 * The SSE stream behind {@code GET /sessions/{sessionId}/events}: reads one session's {@link
 * EventStream} on the request's thread, writes a synthetic {@code Ready} event once the stream is
 * open and then each event named by its subtype, until the stream ends, a write fails (the client
 * is gone) or the caller's identity ends. An ended identity (expired or revoked) closes the stream
 * without a further event: an event is serialized only while the identity holds, and written only
 * if it still does once serialized.
 */
final class SessionEventStream {

  /** The HTTP surface's logger, so one logging configuration covers every route. */
  private static final Logger LOGGER = Logger.getLogger(AgentHttpService.class.getName());

  private final String sessionId;
  private final SseSink sink;
  private final ObjectMapper objectMapper;
  private final CompletableFuture<Void> identityEnded;

  SessionEventStream(
      String sessionId,
      SseSink sink,
      ObjectMapper objectMapper,
      CompletableFuture<Void> identityEnded) {
    this.sessionId = sessionId;
    this.sink = sink;
    this.objectMapper = objectMapper;
    this.identityEnded = identityEnded;
  }

  /**
   * Stream {@code session}'s events to {@code resp} until the stream ends, then close the sink.
   *
   * @param session the session whose events are streamed; non-null
   * @param resp the response the SSE sink is opened on; non-null
   * @param objectMapper serializes each event as the SSE data; non-null
   * @param identityEnded completes when the caller's identity expires or is revoked; non-null
   */
  static void stream(
      AgentSession session,
      ServerResponse resp,
      ObjectMapper objectMapper,
      CompletableFuture<Void> identityEnded) {
    try (var events = session.events()) {
      new SessionEventStream(
              session.sessionId(), resp.sink(SseSink.TYPE), objectMapper, identityEnded)
          .forward(events);
    }
  }

  /**
   * Write {@code Ready}, then every event of {@code events} until it ends or a write fails, then
   * close the sink. The caller's identity ending closes {@code events}.
   */
  void forward(EventStream events) {
    identityEnded.thenRun(events::close);
    try {
      if (write(() -> SseEvent.builder().name("Ready").data("{}").build())) {
        for (var event : events) {
          if (!write(() -> serialized(event))) {
            break;
          }
        }
      }
    } finally {
      try {
        sink.close();
      } catch (RuntimeException ignored) {
        // Helidon closes the sink itself when the client disconnected
      }
    }
  }

  /**
   * Serialize and write {@code event} while the caller's identity holds, checked again once the
   * event is serialized.
   *
   * @return {@code false} if the identity ended or the write failed, which ends the stream
   */
  private boolean write(Supplier<SseEvent> event) {
    try {
      if (identityEnded.isDone()) {
        return false;
      }
      var serialized = event.get();
      if (identityEnded.isDone()) {
        return false;
      }
      sink.emit(serialized);
      return true;
    } catch (RuntimeException e) {
      if (!isDisconnect(e)) {
        LOGGER.log(Level.WARNING, "SSE emit failed for session " + sessionId, e);
      }
      return false;
    }
  }

  private SseEvent serialized(QueryEvent event) {
    return SseEvent.builder()
        .name(event.getClass().getSimpleName())
        .data(objectMapper.writeValueAsString(event))
        .build();
  }

  /** Helidon wraps every socket write failure in {@code ServerConnectionException}. */
  static boolean isDisconnect(Throwable ex) {
    for (var current = ex; current != null; current = current.getCause()) {
      if (current instanceof CloseConnectionException) {
        return true;
      }
    }
    return false;
  }
}
