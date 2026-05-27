/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import ai.singlr.core.model.Response;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.execution.ExecuteTool;
import ai.singlr.session.hooks.HookContext;
import ai.singlr.session.hooks.HookOutcome;
import ai.singlr.session.hooks.PostToolUseHook;
import ai.singlr.session.hooks.PreStopHook;
import ai.singlr.session.hooks.RequireSignatureHook;

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
