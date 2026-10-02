/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the "session hangs past {@code maxWallClock}" bug surfaced in a client
 * stack trace where the JVM main thread parked at {@code AgentSession.runBlocking} → {@code
 * CompletableFuture.join()} for 494 s despite a 2-minute wall-clock cap. Root cause: {@link
 * com.standardapplied.helios.session.loop.StopClassifier} checks {@code maxWallClock} only between
 * turns, while {@link com.standardapplied.helios.session.loop.TurnSubscriber#awaitDone()} blocks
 * indefinitely waiting for a provider stream that may never deliver {@code onComplete} / {@code
 * onError} (silent socket, hung proxy, mid-stream stall).
 *
 * <p>The test constructs an inline {@link Model} whose {@code chatStream} returns a publisher that
 * calls {@code onSubscribe} and then never emits another signal — the bare-minimum hang. The only
 * limit short enough to end that wait is the 500 ms {@code maxWallClock}; the stream-idle timeout
 * is an hour. Pre-fix the result never settles and the hang guard fails the test; post-fix the
 * terminal is {@link ResultMessage.ErrorMaxWallClock}.
 */
final class AgentSessionWallClockTest {

  @Test
  void maxWallClockTerminatesHangingChatStream() {
    var options =
        SessionOptions.newBuilder()
            .withModel(hangingChatStreamModel())
            .withSessionId("sess-hang-wallclock")
            .withLimits(
                SessionLimits.newBuilder()
                    .withMaxWallClock(Duration.ofMillis(500))
                    .withStreamIdleTimeout(Duration.ofHours(1))
                    .build())
            .build();

    try (var session = AgentSession.create(options)) {
      session.send(UserMessage.text("hi"));
      var terminal = Await.value("the wall-clock terminal", session.result());
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
                  public void request(long n) {}

                  @Override
                  public void cancel() {}
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
