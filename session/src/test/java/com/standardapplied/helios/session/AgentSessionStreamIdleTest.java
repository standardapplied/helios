/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.BlockingModel;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the second hung-session failure mode: a model stream that emits a few
 * chunks and then goes silent without delivering {@code onComplete} / {@code onError}. The
 * session's {@link SessionLimits#streamIdleTimeout()} bounds how long the loop waits between
 * chunks; without it, only {@link SessionLimits#maxWallClock()} would eventually terminate the
 * session — typically minutes after the agent has stopped making progress. Before the first chunk
 * the idle clock does not run: a model may think before it answers, and that wait is the wall
 * clock's.
 *
 * <p>The tests construct an inline {@link Model} whose {@code chatStream} optionally emits a single
 * text chunk and then never signals again. After a chunk the only limit short enough to end that
 * wait is the 300 ms {@code streamIdleTimeout}, and {@code maxWallClock} is an hour: the terminal
 * must be the idle watchdog's {@link TimeoutException}, surfaced as {@link
 * ResultMessage.ErrorDuringExecution}.
 */
final class AgentSessionStreamIdleTest {

  /**
   * Zero-chunk case: provider opens the stream, calls {@code onSubscribe}, then is forever silent.
   * The idle timeout is a millisecond and the wall clock half a second; the wall clock ends it.
   */
  @Test
  void aStreamSilentBeforeItsFirstChunkIsEndedByTheWallClockNotTheIdleTimeout() {
    var terminal =
        terminalOf(
            "sess-idle-zero",
            silentStreamModel(),
            SessionLimits.newBuilder()
                .withMaxWallClock(Duration.ofMillis(500))
                .withStreamIdleTimeout(Duration.ofMillis(1))
                .build());

    assertInstanceOf(ResultMessage.ErrorMaxWallClock.class, terminal);
  }

  /**
   * Mid-stream stall: provider emits a chunk, then goes silent. The chunk-arrival timer must reset
   * on the first chunk and then re-fire when the next never arrives. Catches a partial fix that
   * only handles the zero-chunk case.
   */
  @Test
  void midStreamStallFailsWithinStreamIdleTimeout() {
    assertIdleTimeout(
        terminalOf(
            "sess-idle-mid",
            stallsAfterFirstChunkModel(),
            SessionLimits.newBuilder()
                .withMaxWallClock(Duration.ofHours(1))
                .withStreamIdleTimeout(Duration.ofMillis(300))
                .build()));
  }

  private static ResultMessage terminalOf(String sessionId, Model model, SessionLimits limits) {
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSessionId(sessionId)
            .withLimits(limits)
            .build();
    try (var session = AgentSession.create(options)) {
      session.send(UserMessage.text("hi"));
      return Await.value("the terminal", session.result());
    }
  }

  private static void assertIdleTimeout(ResultMessage terminal) {
    var failure =
        assertInstanceOf(
            ResultMessage.ErrorDuringExecution.class,
            terminal,
            () -> "a silent stream must surface ErrorDuringExecution, got " + terminal);
    assertEquals(TimeoutException.class.getName(), failure.error().kind());
    assertTrue(
        failure.error().message().contains("streamIdleTimeout"),
        () -> "the idle watchdog must be what ended the turn: " + failure.error().message());
  }

  // ── inline models ────────────────────────────────────────────────────────

  private static Model silentStreamModel() {
    return baseModel(
        subscriber ->
            subscriber.onSubscribe(
                new Flow.Subscription() {
                  @Override
                  public void request(long n) {}

                  @Override
                  public void cancel() {}
                }));
  }

  private static Model stallsAfterFirstChunkModel() {
    return baseModel(
        subscriber ->
            subscriber.onSubscribe(
                new Flow.Subscription() {
                  @Override
                  public void request(long n) {
                    subscriber.onNext(new ModelChunk.TextDelta("hi "));
                  }

                  @Override
                  public void cancel() {}
                }));
  }

  private static Model baseModel(Flow.Publisher<ModelChunk> stream) {
    return new BlockingModel() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        throw new UnsupportedOperationException("chat() not used by the streaming loop");
      }

      @Override
      public Flow.Publisher<ModelChunk> chatStream(
          List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
        return stream;
      }

      @Override
      public String id() {
        return "test-idle";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }
}
