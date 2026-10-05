/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import io.helidon.http.sse.SseEvent;
import io.helidon.webserver.CloseConnectionException;
import io.helidon.webserver.sse.SseSink;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Characterizes what the HTTP surface logs: every warning goes to {@link AgentHttpService}'s
 * logger, whichever type does the work, so one logging configuration covers every route.
 */
final class AgentHttpServiceLoggingTest {

  private static final Logger LOGGER = Logger.getLogger(AgentHttpService.class.getName());
  private static final String SESSION_ID = "sess-logging";
  private static final QueryEvent EVENT =
      new QueryEvent.AssistantText(SESSION_ID, 0L, Instant.EPOCH, "hello");

  private final List<LogRecord> records = new CopyOnWriteArrayList<>();
  private final Handler handler =
      new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
          records.add(logRecord);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };
  private Level previousLevel;

  @BeforeEach
  void captureTheLog() {
    previousLevel = LOGGER.getLevel();
    handler.setLevel(Level.ALL);
    LOGGER.setLevel(Level.ALL);
    LOGGER.addHandler(handler);
  }

  @AfterEach
  void releaseTheLog() {
    LOGGER.removeHandler(handler);
    LOGGER.setLevel(previousLevel);
  }

  @Test
  void aFailedEmitIsLoggedAsAWarningWithItsCause() {
    var failure = new IllegalStateException("sink broke");
    var stream = new SessionEventStream(SESSION_ID, failingSink(failure), new ObjectMapper());

    stream.onNext(EVENT);

    var logged = onlyRecordForTheSession();
    assertEquals(Level.WARNING, logged.getLevel());
    assertEquals("SSE emit failed for session " + SESSION_ID, logged.getMessage());
    assertSame(failure, logged.getThrown());
  }

  @Test
  void aDisconnectIsNotLogged() {
    var stream =
        new SessionEventStream(
            SESSION_ID,
            failingSink(new CloseConnectionException("peer closed")),
            new ObjectMapper());

    stream.onNext(EVENT);

    assertTrue(recordsForTheSession().isEmpty(), () -> "logged: " + recordsForTheSession());
  }

  @Test
  void aPublisherErrorIsLoggedAsAWarningWithTheError() {
    var error = new IllegalStateException("publisher broke");
    var stream = new SessionEventStream(SESSION_ID, failingSink(error), new ObjectMapper());

    stream.onError(error);

    var logged = onlyRecordForTheSession();
    assertEquals(Level.WARNING, logged.getLevel());
    assertEquals("events publisher errored for session " + SESSION_ID, logged.getMessage());
    assertSame(error, logged.getThrown());
  }

  @Test
  void aFailedResultIsLoggedAsAWarningWithTheFailure() {
    var failure = new IllegalStateException("backend exploded");
    var future = new CompletableFuture<ResultMessage>();
    future.completeExceptionally(failure);

    ResultLongPoll.awaitResult(future, 5L, SESSION_ID);

    var logged = onlyRecordForTheSession();
    assertEquals(Level.WARNING, logged.getLevel());
    assertEquals(
        "session " + SESSION_ID + " result future failed exceptionally", logged.getMessage());
    assertSame(failure, assertInstanceOf(ExecutionException.class, logged.getThrown()).getCause());
  }

  private LogRecord onlyRecordForTheSession() {
    var forTheSession = recordsForTheSession();
    assertEquals(1, forTheSession.size(), () -> "logged: " + forTheSession);
    var logged = forTheSession.getFirst();
    assertEquals(AgentHttpService.class.getName(), logged.getLoggerName());
    return logged;
  }

  private List<LogRecord> recordsForTheSession() {
    return records.stream().filter(r -> r.getMessage().contains(SESSION_ID)).toList();
  }

  private static SseSink failingSink(RuntimeException failure) {
    return new SseSink() {
      @Override
      public SseSink emit(SseEvent event) {
        throw failure;
      }

      @Override
      public void close() {}
    };
  }
}
