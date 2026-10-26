/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.test;

import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.MockModel;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import java.util.Map;

/**
 * What a test hands a hook that observes tool calls and gates the stop: a hook context on turn 0 of
 * session {@code sess}, a tool call by name and a final text response; and a {@code PreToolUse}
 * hook under a name of the test's choosing.
 */
public final class HookInputs {

  private static final MockModel MODEL = new MockModel("");

  private record Named(String name, int priority, PreToolUseHook body) implements PreToolUseHook {

    @Override
    public HookOutcome.PreToolUse beforeTool(ToolCall call, HookContext ctx) {
      return body.beforeTool(call, ctx);
    }
  }

  private HookInputs() {}

  /**
   * A context for session {@code sess}, turn 0, with a fresh cancellation token.
   *
   * @return the context
   */
  public static HookContext context() {
    return new DefaultHookContext("sess", 0, new CancellationToken(), MODEL);
  }

  /**
   * A call of the named tool with no arguments, its id {@code c-<name>}.
   *
   * @param name the tool's name
   * @return the call
   */
  public static ToolCall call(String name) {
    return new ToolCall("c-" + name, name, Map.of());
  }

  /**
   * A {@code PreToolUse} hook named {@code name}, at {@code body}'s priority, that runs {@code
   * body}.
   *
   * @param name the hook's name
   * @param body what the hook does
   * @return the hook
   */
  public static PreToolUseHook named(String name, PreToolUseHook body) {
    return new Named(name, body.priority(), body);
  }

  /**
   * A {@code PreToolUse} hook named {@code name}, at {@code priority}, that runs {@code body}.
   *
   * @param name the hook's name
   * @param priority the hook's priority
   * @param body what the hook does
   * @return the hook
   */
  public static PreToolUseHook named(String name, int priority, PreToolUseHook body) {
    return new Named(name, priority, body);
  }

  /**
   * A response that ends the turn with the text {@code done} and no tool call.
   *
   * @return the response
   */
  public static Response<Void> stopResponse() {
    return Response.newBuilder().withContent("done").build();
  }
}
