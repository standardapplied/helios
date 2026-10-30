/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.CancellationToken;
import com.standardapplied.helios.core.tool.SessionContext;
import com.standardapplied.helios.core.trace.Trace;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.loop.StoredHistory;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/** A session builds a trace recorder only when its options name a trace listener. */
final class SessionWiringTest {

  private static final String SID = "sess-wiring";

  private static SessionWiring wiring(SessionOptions.Builder options) {
    var token = new CancellationToken();
    var state = new SessionState(SID, token, Clock.systemUTC());
    var events = new SessionEventPublisher(SID);
    return new SessionWiring(
        options.build(),
        new SessionContext(SID, token, Clock.systemUTC(), new SecretRegistry()),
        new PendingQuestions(() -> state, events, Clock.systemUTC()),
        new SteeringQueue(4),
        events,
        state.ending(),
        StoredHistory.none());
  }

  private static SessionOptions.Builder options() {
    return SessionOptions.newBuilder()
        .withModel(ScriptedModel.newBuilder().build())
        .withSessionId(SID);
  }

  @Test
  void withoutATraceListenerNoRecorderIsBuilt() {
    assertTrue(wiring(options()).recorder().isEmpty());
  }

  @Test
  void aTraceListenerGetsTheSessionsRecorder() {
    assertTrue(wiring(options().withTraceListener((Trace trace) -> {})).recorder().isPresent());
  }
}
