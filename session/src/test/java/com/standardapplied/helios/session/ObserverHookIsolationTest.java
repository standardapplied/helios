/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.OnStreamEventHook;
import com.standardapplied.helios.session.hooks.OnUserMessageHook;
import com.standardapplied.helios.session.hooks.PostModelTurnHook;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Every phase but {@code PreToolUse} isolates a failing hook: one that throws or returns {@code
 * null} is treated as {@code Continue}, the next hook of its phase runs, and a permitted tool call
 * of the same session still runs once.
 */
final class ObserverHookIsolationTest {

  /** The phases whose hooks observe; each builds a hook of its phase that answers with a body. */
  enum Phase {
    PRE_MODEL_TURN,
    POST_MODEL_TURN,
    POST_TOOL_USE,
    PRE_STOP,
    ON_USER_MESSAGE,
    ON_STREAM_EVENT;

    Hook hook(Supplier<HookOutcome.Continue> body) {
      return switch (this) {
        case PRE_MODEL_TURN -> (PreModelTurnHook) (history, ctx) -> body.get();
        case POST_MODEL_TURN -> (PostModelTurnHook) (response, ctx) -> body.get();
        case POST_TOOL_USE -> (PostToolUseHook) (call, result, ctx) -> body.get();
        case PRE_STOP -> (PreStopHook) (response, ctx) -> body.get();
        case ON_USER_MESSAGE -> (OnUserMessageHook) (message, ctx) -> body.get();
        case ON_STREAM_EVENT -> (OnStreamEventHook) (event, ctx) -> body.get();
      };
    }
  }

  private final WriteToolSessions sessions = new WriteToolSessions();
  private final List<Phase> recorded = new CopyOnWriteArrayList<>();

  /** Runs a session through every phase: a user message, a tool round and a stop. */
  private ToolResult runWith(Phase phase, Supplier<HookOutcome.Continue> failing) {
    var options =
        sessions
            .options(
                new Permission(
                    PermissionMode.DEFAULT,
                    List.of(PermissionRule.any("Write")),
                    List.of(),
                    List.of()))
            .withHook(phase.hook(failing))
            .withHook(
                phase.hook(
                    () -> {
                      recorded.add(phase);
                      return HookOutcome.cont();
                    }));
    return WriteToolSessions.run(options, WriteToolSessions.write("/w")).onlyResult();
  }

  private void assertIsolated(Phase phase, Supplier<HookOutcome.Continue> failing) {
    var result = runWith(phase, failing);

    assertFalse(recorded.isEmpty(), "the recording hook of " + phase + " fired");
    assertEquals(new ToolOutcome.Succeeded(), result.outcome());
    assertEquals(1, sessions.executed.size());
  }

  @ParameterizedTest
  @EnumSource(Phase.class)
  void aHookThatThrowsIsSkipped(Phase phase) {
    assertIsolated(
        phase,
        () -> {
          throw new IllegalStateException("observer broke");
        });
  }

  @ParameterizedTest
  @EnumSource(value = Phase.class, mode = EnumSource.Mode.EXCLUDE, names = "ON_STREAM_EVENT")
  void aHookThatReturnsNullIsSkipped(Phase phase) {
    assertIsolated(phase, () -> null);
  }
}
