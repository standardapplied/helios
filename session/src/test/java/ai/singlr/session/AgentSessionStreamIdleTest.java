/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelChunk;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.Tool;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the second hung-session failure mode: a model stream that emits a few
 * chunks (or zero) and then goes silent without delivering {@code onComplete} / {@code onError}.
 * The session's {@link SessionLimits#streamIdleTimeout()} bounds how long the loop waits between
 * chunks; without it, only {@link SessionLimits#maxWallClock()} would eventually terminate the
 * session — typically minutes after the agent has stopped making progress.
 *
 * <p>The tests construct an inline {@link Model} whose {@code chatStream} optionally emits a single
 * text chunk and then never signals again. With a 300 ms {@code streamIdleTimeout} the turn must
 * end in {@link ResultMessage.ErrorDuringExecution} within a few seconds. {@code maxWallClock} here
 * is intentionally set to a long-enough value that it is NOT the trigger — only the per-chunk idle
 * deadline can.
 */
final class AgentSessionStreamIdleTest {

  /**
   * Zero-chunk case: provider opens the stream, calls {@code onSubscribe}, then is forever silent.
   * Distinct from the wall-clock test because the wall-clock here is generous (10 s); only the idle
   * deadline (300 ms) can produce a sub-second terminal.
   */
  @Test
  void zeroChunkStreamFailsWithinStreamIdleTimeout() throws Exception {
    var options =
        SessionOptions.newBuilder()
            .withModel(silentStreamModel())
            .withSessionId("sess-idle-zero")
            .withClock(Clock.systemUTC())
            .withLimits(
                SessionLimits.newBuilder()
                    .withMaxWallClock(Duration.ofSeconds(10))
                    .withStreamIdleTimeout(Duration.ofMillis(300))
                    .build())
            .build();

    try (var session = AgentSession.create(options)) {
      session.send(UserMessage.text("hi"));
      var t0 = System.nanoTime();
      var terminal = session.result().get(5, TimeUnit.SECONDS);
      var elapsedMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();

      assertInstanceOf(
          ResultMessage.ErrorDuringExecution.class,
          terminal,
          () ->
              "silent stream must surface ErrorDuringExecution within streamIdleTimeout, got "
                  + terminal.getClass().getSimpleName());
      assertTrue(
          elapsedMs < 3_000,
          () ->
              "idle deadline (300 ms) must fire well before maxWallClock (10 s); saw "
                  + elapsedMs
                  + " ms");
    }
  }

  /**
   * Mid-stream stall: provider emits a chunk, then goes silent. The chunk-arrival timer must reset
   * on the first chunk and then re-fire when the next never arrives. Catches a partial fix that
   * only handles the zero-chunk case.
   */
  @Test
  void midStreamStallFailsWithinStreamIdleTimeout() throws Exception {
    var options =
        SessionOptions.newBuilder()
            .withModel(stallsAfterFirstChunkModel())
            .withSessionId("sess-idle-mid")
            .withClock(Clock.systemUTC())
            .withLimits(
                SessionLimits.newBuilder()
                    .withMaxWallClock(Duration.ofSeconds(10))
                    .withStreamIdleTimeout(Duration.ofMillis(300))
                    .build())
            .build();

    try (var session = AgentSession.create(options)) {
      session.send(UserMessage.text("hi"));
      var t0 = System.nanoTime();
      var terminal = session.result().get(5, TimeUnit.SECONDS);
      var elapsedMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();

      assertInstanceOf(ResultMessage.ErrorDuringExecution.class, terminal);
      assertTrue(
          elapsedMs < 3_000,
          () -> "mid-stream stall must trip the idle deadline; saw " + elapsedMs + " ms");
    }
  }

  // ── inline models ────────────────────────────────────────────────────────

  private static Model silentStreamModel() {
    return baseModel(
        subscriber ->
            subscriber.onSubscribe(
                new Flow.Subscription() {
                  @Override
                  public void request(long n) {
                    // Drop the demand on the floor.
                  }

                  @Override
                  public void cancel() {}
                }));
  }

  private static Model stallsAfterFirstChunkModel() {
    return baseModel(
        subscriber -> {
          subscriber.onSubscribe(
              new Flow.Subscription() {
                @Override
                public void request(long n) {
                  // Deliver exactly one chunk on the first demand, then go silent.
                  subscriber.onNext(new ModelChunk.TextDelta("hi "));
                }

                @Override
                public void cancel() {}
              });
        });
  }

  private static Model baseModel(Flow.Publisher<ModelChunk> stream) {
    return new Model() {
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

  // Suppress unused-import lint: kept for callers that introspect Usage in future variants.
  @SuppressWarnings("unused")
  private static Usage zeroUsage() {
    return Usage.of(0, 0);
  }
}
