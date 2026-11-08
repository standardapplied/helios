/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.session.ContextCompactor;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.store.SessionEntry;
import com.standardapplied.helios.session.store.SessionStore;
import com.standardapplied.helios.session.store.SessionStoreException;
import com.standardapplied.helios.session.store.StoredSession;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How the loop's end decision meets a store that refuses the write of a turn the run goes on from.
 */
final class AgentLoopStoredHistoryTest {

  private final LoopFixture fixture =
      new LoopFixture("chat-1", Instant.parse("2026-11-08T10:00:00Z"));
  private final SteeringQueue queue = new SteeringQueue(8);

  @Test
  void aRefusedWriteOfATurnTheRunGoesOnFromEndsTheRunThroughTheBoundary() {
    var state = fixture.state();
    var script = ScriptedModel.newBuilder().withTextTurn("one").withTextTurn("two").build();
    var steered =
        LoopModels.onEachCall(
            script,
            call -> {
              if (call == 1) {
                queue.offer(UserMessage.text("again"));
              }
            });
    var collaborators =
        fixture.collaborators(HookRegistry.empty(), fixture.dispatch(ToolRegistry.empty()), queue);
    var loop =
        new AgentLoop(
            collaborators,
            fixture.runner(collaborators, steered),
            new StopClassifier(collaborators.secrets()),
            TokenCounter.charBased(),
            ContextCompactor.disabled(),
            StoredHistory.open(new RefusingStore(), state));
    var decisions = new ArrayList<Boolean>();
    queue.offer(UserMessage.text("first"));

    var result =
        loop.run(
            state,
            SessionLimits.defaults(),
            naturalStop -> {
              decisions.add(naturalStop);
              return !naturalStop || queue.size() == 0;
            });

    var error = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result).error();
    assertEquals("SessionStoreFailed", error.kind());
    assertEquals(List.of(false), decisions);
    assertEquals(1, script.calls().size());
  }

  /** A store holding no session that refuses every append. */
  private static final class RefusingStore implements SessionStore {

    @Override
    public StoredSession load(String sessionId) {
      return new StoredSession(sessionId, 0, List.of());
    }

    @Override
    public void append(String sessionId, long expectedLastSeq, List<SessionEntry> entries) {
      throw new SessionStoreException("full", null);
    }
  }
}
