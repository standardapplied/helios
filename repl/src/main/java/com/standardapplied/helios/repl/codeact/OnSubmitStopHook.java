/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import ai.singlr.core.model.ToolCall;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.hooks.HookContext;
import ai.singlr.session.hooks.HookOutcome;
import ai.singlr.session.hooks.PostToolUseHook;
import java.util.Objects;

/**
 * {@link PostToolUseHook} that terminates the agent loop with a JSON-serialized {@link
 * SubmittedValueHolder} value as the session's terminal result.
 *
 * <p>Wiring: register on a session whose tools include either the agent-loop {@link SubmitTool} or
 * the {@link ai.singlr.repl.CodeExecutionTool execute_code} surface backed by the in-sandbox {@link
 * SubmitFunction submit} host function. After every tool dispatch, this hook checks the shared
 * holder; if it has just flipped to populated, it serializes the typed value to JSON and returns
 * {@link HookOutcome.Stop} with that JSON as the result. The loop's existing PostToolUse handling
 * turns the Stop into a terminal {@code ResultMessage.Success(json)}; the typed {@code
 * runBlocking(message, schema)} round-trips the JSON back through Jackson into the user's record
 * type.
 *
 * <p>One holder per session; one hook per session. The hook is stateless beyond its reference to
 * the holder, so concurrent dispatch of multiple tools in the same turn is safe — only one will win
 * the holder's set-once race and only one Stop will fire.
 */
public final class OnSubmitStopHook implements PostToolUseHook {

  private final SubmittedValueHolder holder;

  /**
   * Wire the hook to a per-session holder.
   *
   * @param holder the holder shared with the session's submit surface; non-null
   * @throws NullPointerException if {@code holder} is null
   */
  public OnSubmitStopHook(SubmittedValueHolder holder) {
    this.holder = Objects.requireNonNull(holder, "holder must not be null");
  }

  @Override
  public HookOutcome afterTool(ToolCall call, ToolResult result, HookContext ctx) {
    if (!holder.isSubmitted()) {
      return HookOutcome.cont();
    }
    var value = holder.peek().orElse(null);
    if (value == null) {
      return HookOutcome.cont();
    }
    return HookOutcome.stop(SubmitValidation.toJson(value));
  }

  @Override
  public String name() {
    return "OnSubmitStopHook";
  }
}
