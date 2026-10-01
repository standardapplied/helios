/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.repl.codeact;

import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.execution.ExecuteTool;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.hooks.RequireSignatureHook;

/**
 * CodeAct-specific stop gate: refuses termination until the model has actually executed code (i.e.
 * at least one call to the {@link ExecuteTool#NAME Execute} tool has happened in this session). The
 * spec calls this the "CodeAct must actually execute code" invariant.
 *
 * <p>Mechanically a thin wrapper around {@link RequireSignatureHook#withToolName} so the preset
 * surface stays declarative (the spec writes {@code new RequireExecuteCodeHook()} as a no-arg
 * constructor). The delegated hook does the observe-and-check work; this class forwards both phase
 * contracts to it.
 */
public final class RequireExecuteCodeHook implements PostToolUseHook, PreStopHook {

  private final RequireSignatureHook delegate = RequireSignatureHook.withToolName(ExecuteTool.NAME);

  /** Construct a fresh hook. Pre-bound to the {@code Execute} tool — no configuration needed. */
  public RequireExecuteCodeHook() {}

  @Override
  public HookOutcome afterTool(ToolCall call, ToolResult result, HookContext ctx) {
    return delegate.afterTool(call, result, ctx);
  }

  @Override
  public HookOutcome beforeStop(Response<?> stopResponse, HookContext ctx) {
    return delegate.beforeStop(stopResponse, ctx);
  }

  @Override
  public String name() {
    return "RequireExecuteCodeHook";
  }

  /**
   * Whether the model has called {@code Execute} at least once in this session. Useful for tests
   * and diagnostics.
   *
   * @return {@code true} if the requirement is currently satisfied
   */
  public boolean hasExecutedCode() {
    return delegate.observed().contains(ExecuteTool.NAME);
  }
}
