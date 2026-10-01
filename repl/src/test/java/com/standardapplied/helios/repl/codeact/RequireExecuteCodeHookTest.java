/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.repl.codeact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.execution.ExecuteTool;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.hooks.PreStopHook;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RequireExecuteCodeHookTest {

  private static final Model STUB_MODEL =
      new Model() {
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

  private static HookContext ctx() {
    return new DefaultHookContext("sess", 0, new CancellationToken(), STUB_MODEL);
  }

  private static ToolCall call(String name) {
    return new ToolCall("c-" + name, name, Map.of());
  }

  private static Response<Void> stop() {
    return Response.newBuilder().withContent("done").build();
  }

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
    assertInstanceOf(HookOutcome.Inject.class, hook.beforeStop(stop(), ctx()));
  }

  @Test
  void unrelatedToolDoesNotSatisfy() {
    var hook = new RequireExecuteCodeHook();
    hook.afterTool(call("Other"), ToolResult.success("x"), ctx());
    assertFalse(hook.hasExecutedCode());
    assertInstanceOf(HookOutcome.Inject.class, hook.beforeStop(stop(), ctx()));
  }

  @Test
  void executeCodeCallFlipsState() {
    var hook = new RequireExecuteCodeHook();
    hook.afterTool(call(ExecuteTool.NAME), ToolResult.success("ok"), ctx());
    assertTrue(hook.hasExecutedCode());
    assertInstanceOf(HookOutcome.Continue.class, hook.beforeStop(stop(), ctx()));
  }

  @Test
  void injectMessageMentionsExecuteCode() {
    var hook = new RequireExecuteCodeHook();
    var decision = hook.beforeStop(stop(), ctx());
    var msg = ((HookOutcome.Inject) decision).userMessage();
    assertTrue(msg.contains(ExecuteTool.NAME));
  }

  @Test
  void carriesStableName() {
    assertEquals("RequireExecuteCodeHook", new RequireExecuteCodeHook().name());
  }
}
