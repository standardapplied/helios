/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the "session hangs past {@code maxWallClock}" bug surfaced in a client
 * stack trace where the JVM main thread parked at {@code AgentSession.runBlocking} → {@code
 * CompletableFuture.join()} for 494 s despite a 2-minute wall-clock cap. Root cause: {@link
 * com.standardapplied.helios.session.loop.StopClassifier} checks {@code maxWallClock} only between
 * turns, while the loop waited indefinitely for a provider stream that may never end (silent
 * socket, hung proxy, mid-stream stall).
 *
 * <p>The model's stream blocks its first read until it is closed — the bare-minimum hang. The only
 * limit short enough to end that wait is the 500 ms {@code maxWallClock}: the terminal is {@link
 * ResultMessage.ErrorMaxWallClock}, and the session closed the stream.
 */
final class AgentSessionWallClockTest {

  @Test
  void maxWallClockTerminatesHangingChatStream() {
    var closed = new CountDownLatch(1);
    var model = ScriptedModel.newBuilder().withStreamTurn(blockingUntilClosed(closed)).build();
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSessionId("sess-hang-wallclock")
            .withLimits(SessionLimits.newBuilder().withMaxWallClock(Duration.ofMillis(500)).build())
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
    Await.latch("the stream closed", closed);
  }

  private static CloseableIterator<ModelChunk> blockingUntilClosed(CountDownLatch closed) {
    return new CloseableIterator<>() {
      @Override
      public boolean hasNext() {
        Await.latch("the stream closed", closed);
        throw new CancellationException("closed");
      }

      @Override
      public ModelChunk next() {
        throw new NoSuchElementException();
      }

      @Override
      public void close() {
        closed.countDown();
      }
    };
  }
}
