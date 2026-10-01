/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.hooks.DefaultHookContext;
import ai.singlr.session.hooks.HookContext;
import ai.singlr.session.hooks.HookRegistry;
import ai.singlr.session.hooks.OnStreamEventHook;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

final class EventEmitterTest {

  private static final Instant FIXED = Instant.parse("2026-05-15T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);

  private static Model stubModel() {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder().build();
      }

      @Override
      public String id() {
        return "stub";
      }

      @Override
      public String provider() {
        return "stub";
      }
    };
  }

  private static Function<SessionState, HookContext> ctxFactory(Model model) {
    return state ->
        new DefaultHookContext(
            state.sessionId(), state.currentTurnIndex(), state.cancellation(), model);
  }

  private static SessionState newState() {
    return new SessionState("sess-emit", new CancellationToken(), CLOCK);
  }

  @Test
  void rejectsNullEventSink() {
    assertThrows(
        NullPointerException.class,
        () -> new EventEmitter(null, HookRegistry.empty(), ctxFactory(stubModel()), CLOCK));
  }

  @Test
  void rejectsNullHooks() {
    assertThrows(
        NullPointerException.class,
        () -> new EventEmitter(e -> {}, null, ctxFactory(stubModel()), CLOCK));
  }

  @Test
  void rejectsNullCtxFactory() {
    assertThrows(
        NullPointerException.class,
        () -> new EventEmitter(e -> {}, HookRegistry.empty(), null, CLOCK));
  }

  @Test
  void rejectsNullClock() {
    assertThrows(
        NullPointerException.class,
        () -> new EventEmitter(e -> {}, HookRegistry.empty(), ctxFactory(stubModel()), null));
  }

  @Test
  void clockAccessorReturnsConfiguredClock() {
    var emitter = new EventEmitter(e -> {}, HookRegistry.empty(), ctxFactory(stubModel()), CLOCK);
    assertEquals(CLOCK, emitter.clock());
  }

  @Test
  void emitForwardsToSinkAndFiresStreamHook() {
    var sinkEvents = new ArrayList<QueryEvent>();
    var hookEvents = new ArrayList<QueryEvent>();
    OnStreamEventHook hook = (ev, ctx) -> hookEvents.add(ev);
    var emitter =
        new EventEmitter(
            sinkEvents::add, new HookRegistry(List.of(hook)), ctxFactory(stubModel()), CLOCK);
    var state = newState();
    var event = new QueryEvent.AssistantText("sess-emit", 0L, FIXED, "hi");
    emitter.emit(state, event);
    assertEquals(1, sinkEvents.size());
    assertEquals(1, hookEvents.size());
    assertEquals(event, sinkEvents.get(0));
    assertEquals(event, hookEvents.get(0));
  }

  @Test
  void emitHookFiredFallsBackToPhaseWhenHookNameNull() {
    var sinkEvents = new ArrayList<QueryEvent>();
    var emitter =
        new EventEmitter(sinkEvents::add, HookRegistry.empty(), ctxFactory(stubModel()), CLOCK);
    emitter.emitHookFired(newState(), null, "PreToolUseHook", "Block");
    assertEquals(1, sinkEvents.size());
    var fired = (QueryEvent.HookFired) sinkEvents.get(0);
    assertEquals("PreToolUseHook", fired.hookName(), "falls back to phase when hookName is null");
    assertEquals("PreToolUseHook", fired.phase());
    assertEquals("Block", fired.outcomeKind());
  }

  @Test
  void emitHookFiredUsesProvidedHookName() {
    var sinkEvents = new ArrayList<QueryEvent>();
    var emitter =
        new EventEmitter(sinkEvents::add, HookRegistry.empty(), ctxFactory(stubModel()), CLOCK);
    emitter.emitHookFired(newState(), "MyGuard", "PreToolUseHook", "Stop");
    var fired = (QueryEvent.HookFired) sinkEvents.get(0);
    assertEquals("MyGuard", fired.hookName(), "hookName wins when non-null");
    assertEquals("PreToolUseHook", fired.phase());
    assertEquals("Stop", fired.outcomeKind());
  }

  @Test
  void emittedEventCarriesClockTimestamp() {
    var sinkEvents = new ArrayList<QueryEvent>();
    var emitter =
        new EventEmitter(sinkEvents::add, HookRegistry.empty(), ctxFactory(stubModel()), CLOCK);
    emitter.emitHookFired(newState(), "X", "P", "K");
    assertEquals(FIXED, sinkEvents.get(0).timestamp());
    assertNotNull(sinkEvents.get(0).sessionId());
    assertTrue(sinkEvents.get(0).sessionId().startsWith("sess-"));
  }
}
