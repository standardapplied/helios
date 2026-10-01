/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.tool;

import java.util.Map;

/**
 * Functional interface for tool execution. Arguments come from the model as a {@code Map} (parsed
 * from JSON); the {@link ToolContext} carries the session's {@link
 * ai.singlr.core.runtime.CancellationToken CancellationToken} and the per-call deadline.
 *
 * <p>Tools that perform long-running I/O should poll {@code ctx.cancellation()} at safe points so
 * that an {@code AgentSession.interrupt(...)} or session close propagates promptly. Tools that
 * complete in a single fast step can ignore the context entirely.
 */
@FunctionalInterface
public interface ToolExecutor {

  /**
   * Execute the tool with the given arguments and per-invocation context.
   *
   * @param arguments the arguments from the model; non-null
   * @param context the per-call context (cancellation + deadline); non-null
   * @return the result of the execution
   */
  ToolResult execute(Map<String, Object> arguments, ToolContext context);
}
