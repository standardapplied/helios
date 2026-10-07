/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.repl.codeact;

import static com.standardapplied.helios.session.test.HookInputs.call;
import static com.standardapplied.helios.session.test.HookInputs.context;
import static com.standardapplied.helios.session.test.HookInputs.stopResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.execution.ExecuteTool;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.hooks.PreStopHook;
import org.junit.jupiter.api.Test;

final class RequireExecuteCodeHookTest {

  @Test
  void implementsBothHookPhases() {
    var hook = new RequireExecuteCodeHook();
    assertInstanceOf(PostToolUseHook.class, hook);
    assertInstanceOf(PreStopHook.class, hook);
  }

  @Test
  void unmetByDefault() {
    var hook = new RequireExecuteCodeHook();
    assertFalse(hook.hasExecutedCode());
    assertInstanceOf(HookOutcome.Inject.class, hook.beforeStop(stopResponse(), context()));
  }

  @Test
  void unrelatedToolDoesNotSatisfy() {
    var hook = new RequireExecuteCodeHook();
    hook.afterTool(call("Other"), ToolResult.success("x"), context());
    assertFalse(hook.hasExecutedCode());
    assertInstanceOf(HookOutcome.Inject.class, hook.beforeStop(stopResponse(), context()));
  }

  @Test
  void executeCodeCallFlipsState() {
    var hook = new RequireExecuteCodeHook();
    hook.afterTool(call(ExecuteTool.NAME), ToolResult.success("ok"), context());
    assertTrue(hook.hasExecutedCode());
    assertInstanceOf(HookOutcome.Continue.class, hook.beforeStop(stopResponse(), context()));
  }

  @Test
  void injectMessageMentionsExecuteCode() {
    var hook = new RequireExecuteCodeHook();
    var decision = hook.beforeStop(stopResponse(), context());
    var msg = ((HookOutcome.Inject) decision).userMessage();
    assertTrue(msg.contains(ExecuteTool.NAME));
  }

  @Test
  void carriesStableName() {
    assertEquals("RequireExecuteCodeHook", new RequireExecuteCodeHook().name());
  }
}
