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
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.Tool;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the "session hangs past {@code maxWallClock}" bug surfaced in a client
 * stack trace where the JVM main thread parked at {@code AgentSession.runBlocking} → {@code
 * CompletableFuture.join()} for 494 s despite a 2-minute wall-clock cap. Root cause: {@link
 * ai.singlr.session.loop.StopClassifier} checks {@code maxWallClock} only between turns, while
 * {@link ai.singlr.session.loop.TurnSubscriber#awaitDone()} blocks indefinitely waiting for a
 * provider stream that may never deliver {@code onComplete} / {@code onError} (silent socket, hung
 * proxy, mid-stream stall).
 *
 * <p>The tests in this class construct an inline {@link Model} whose {@code chatStream} returns a
 * publisher that calls {@code onSubscribe} and then never emits another signal — the bare-minimum
 * hang. A short {@code maxWallClock} (500 ms) combined with a {@code result().get(5 s)} cap on the
 * test side proves the bug deterministically: pre-fix the {@code get} call throws {@link
 * java.util.concurrent.TimeoutException}; post-fix the terminal is {@link
 * ResultMessage.ErrorMaxWallClock} within ~500 ms.
 */
final class AgentSessionWallClockTest {

  /**
   * The "main" reproduction. An inline Model whose chatStream is forever silent must still let the
   * session terminate with {@link ResultMessage.ErrorMaxWallClock} once {@code maxWallClock}
   * elapses — not hang.
   */
  @Test
  void maxWallClockTerminatesHangingChatStream() throws Exception {
    var options =
        SessionOptions.newBuilder()
            .withModel(hangingChatStreamModel())
            .withSessionId("sess-hang-wallclock")
            .withClock(Clock.systemUTC())
            .withLimits(SessionLimits.newBuilder().withMaxWallClock(Duration.ofMillis(500)).build())
            .build();

    try (var session = AgentSession.create(options)) {
      session.send(UserMessage.text("hi"));
      var terminal = session.result().get(5, TimeUnit.SECONDS);
      assertInstanceOf(
          ResultMessage.ErrorMaxWallClock.class,
          terminal,
          () ->
              "session must surface ErrorMaxWallClock when chatStream hangs past maxWallClock — "
                  + "got "
                  + terminal.getClass().getSimpleName()
                  + ": "
                  + terminal);
    }
  }

  /**
   * Variant that bounds the test from above with {@link java.util.concurrent.Future#get(long,
   * TimeUnit)}: ensures the actual terminal lands close to {@code maxWallClock}, not minutes later.
   * Catches a partial fix that produces the right terminal type but only after, say, the implicit
   * 1-hour default fires.
   */
  @Test
  void maxWallClockTerminationHappensNearTheDeadline() throws Exception {
    var maxWallClock = Duration.ofMillis(500);
    var options =
        SessionOptions.newBuilder()
            .withModel(hangingChatStreamModel())
            .withSessionId("sess-hang-wallclock-deadline")
            .withClock(Clock.systemUTC())
            .withLimits(SessionLimits.newBuilder().withMaxWallClock(maxWallClock).build())
            .build();

    try (var session = AgentSession.create(options)) {
      session.send(UserMessage.text("hi"));
      var t0 = System.nanoTime();
      var terminal = session.result().get(5, TimeUnit.SECONDS);
      var elapsedMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
      assertInstanceOf(ResultMessage.ErrorMaxWallClock.class, terminal);
      assertTrue(
          elapsedMs < 3_000,
          () ->
              "terminal must land within a few seconds of the 500 ms deadline; saw "
                  + elapsedMs
                  + " ms");
    }
  }

  // ── inline Model whose chatStream hangs forever ──────────────────────────

  private static Model hangingChatStreamModel() {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        throw new UnsupportedOperationException("chat() not used by the streaming loop");
      }

      @Override
      public Flow.Publisher<ModelChunk> chatStream(
          List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
        return subscriber ->
            subscriber.onSubscribe(
                new Flow.Subscription() {
                  @Override
                  public void request(long n) {
                    // Drop the demand on the floor — never emit a chunk.
                  }

                  @Override
                  public void cancel() {
                    // No-op; the subscriber has no way to wake up the runner from here.
                  }
                });
      }

      @Override
      public String id() {
        return "test-hanging";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }
}
