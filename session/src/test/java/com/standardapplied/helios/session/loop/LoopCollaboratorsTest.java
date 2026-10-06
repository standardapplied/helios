/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.MockModel;
import com.standardapplied.helios.session.ConcurrencyLimits;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookRegistry;
import com.standardapplied.helios.session.tools.ToolRegistry;
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
          ConcurrencyLimits.defaults());
  private final Function<SessionState, HookContext> contextFactory =
      s ->
          new DefaultHookContext(
              s.sessionId(), s.currentTurnIndex(), s.cancellation(), new MockModel("unused"));

  @Test
  void nullHooksRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new LoopCollaborators(null, dispatch, queue, events::add, contextFactory, CLOCK));
    assertEquals("hooks must not be null", ex.getMessage());
  }

  @Test
  void nullToolDispatchRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new LoopCollaborators(hooks, null, queue, events::add, contextFactory, CLOCK));
    assertEquals("toolDispatch must not be null", ex.getMessage());
  }

  @Test
  void nullSteeringQueueRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new LoopCollaborators(hooks, dispatch, null, events::add, contextFactory, CLOCK));
    assertEquals("steeringQueue must not be null", ex.getMessage());
  }

  @Test
  void nullEventSinkRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new LoopCollaborators(hooks, dispatch, queue, null, contextFactory, CLOCK));
    assertEquals("eventSink must not be null", ex.getMessage());
  }

  @Test
  void nullHookContextFactoryRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new LoopCollaborators(hooks, dispatch, queue, events::add, null, CLOCK));
    assertEquals("hookContextFactory must not be null", ex.getMessage());
  }

  @Test
  void nullClockRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new LoopCollaborators(hooks, dispatch, queue, events::add, contextFactory, null));
    assertEquals("clock must not be null", ex.getMessage());
  }

  @Test
  void builderCarriesEveryComponent() {
    var collaborators =
        LoopCollaborators.newBuilder()
            .withHooks(hooks)
            .withToolDispatch(dispatch)
            .withSteeringQueue(queue)
            .withEventSink(events::add)
            .withHookContextFactory(contextFactory)
            .withClock(CLOCK)
            .build();

    assertSame(hooks, collaborators.hooks());
    assertSame(dispatch, collaborators.toolDispatch());
    assertSame(queue, collaborators.steeringQueue());
    assertSame(contextFactory, collaborators.hookContextFactory());
    assertSame(CLOCK, collaborators.clock());
  }

  @Test
  void builderDefaultsTheClockToTheSystemClock() {
    var collaborators =
        LoopCollaborators.newBuilder()
            .withHooks(hooks)
            .withToolDispatch(dispatch)
            .withSteeringQueue(queue)
            .withEventSink(events::add)
            .withHookContextFactory(contextFactory)
            .build();

    assertEquals(java.time.Clock.systemUTC(), collaborators.clock());
  }

  @Test
  void builderRejectsAMissingComponent() {
    var builder = LoopCollaborators.newBuilder().withHooks(hooks);

    var ex = assertThrows(NullPointerException.class, builder::build);
    assertEquals("toolDispatch must not be null", ex.getMessage());
  }

  @Test
  void hookContextAndEmitterUseTheSharedFactoryAndSink() {
    var collaborators =
        new LoopCollaborators(hooks, dispatch, queue, events::add, contextFactory, CLOCK);
    var state = new SessionState("sess-collab", new CancellationToken(), CLOCK);

    assertEquals("sess-collab", collaborators.hookContext(state).sessionId());
    collaborators.emitter().emitHookFired(state, "h", "PreToolUseHook", "Block");
    assertTrue(events.getFirst() instanceof QueryEvent.HookFired);
  }
}
