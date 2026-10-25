/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A model stream that never yields a chunk, as a model thinking before it answers does: the session
 * adds no idle clock of its own, so the wall clock is what ends the wait. A provider's stream is
 * bounded by its own {@code ModelConfig.streamIdleTimeout} once its headers arrive.
 */
final class AgentSessionStreamIdleTest {

  @Test
  void aStreamSilentBeforeItsFirstChunkIsEndedByTheWallClockNotTheIdleTimeout() {
    var model = ScriptedModel.newBuilder().withStreamTurn(ModelStreams.stalled(() -> {})).build();
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSessionId("sess-idle-zero")
            .withLimits(SessionLimits.newBuilder().withMaxWallClock(Duration.ofMillis(500)).build())
            .build();

    ResultMessage terminal;
    try (var session = AgentSession.create(options)) {
      session.send(UserMessage.text("hi"));
      terminal = Await.value("the terminal", session.result());
    }

    assertInstanceOf(ResultMessage.ErrorMaxWallClock.class, terminal);
  }
}
