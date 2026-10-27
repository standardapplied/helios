/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.MockModel;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.hooks.HookContext;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

final class LoopCollaboratorsTest {

  private static final InstantSource CLOCK =
      InstantSource.fixed(Instant.parse("2026-05-14T19:00:00Z"));

  private final List<QueryEvent> events = new ArrayList<>();
  private final HookRegistry hooks = HookRegistry.empty();
  private final SteeringQueue queue = new SteeringQueue(8);
  private final ToolDispatch dispatch =
      new ToolDispatch(
          SessionContext.forTesting("loop-collaborators-test"),
          ToolRegistry.empty(),
          new SessionEnding("test", new CancellationToken()));
  private final Function<SessionState, HookContext> contextFactory =
      s ->
          new DefaultHookContext(
              s.sessionId(), s.currentTurnIndex(), s.cancellation(), new MockModel("unused"));

  @Test
  void hookContextAndEmitterUseTheSharedFactoryAndSink() {
    var collaborators =
        new LoopCollaborators(
            hooks,
            dispatch,
            Authorizations.ADMIT_ALL,
            Authorizations.NO_RULE,
            queue,
            events::add,
            contextFactory,
            CLOCK);
    var state = new SessionState("sess-collab", new CancellationToken(), CLOCK);

    assertEquals("sess-collab", collaborators.hookContext(state).sessionId());
    collaborators.emitter().emitHookFired(state, "h", "PreToolUseHook", "Block");
    assertTrue(events.getFirst() instanceof QueryEvent.HookFired);
  }
}
