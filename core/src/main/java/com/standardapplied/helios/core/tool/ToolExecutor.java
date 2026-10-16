/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.tool;

import java.util.Map;

/**
 * Functional interface for tool execution. Arguments come from the model as a {@code Map} (parsed
 * from JSON); the {@link ToolContext} carries the call's {@link
 * com.standardapplied.helios.core.runtime.CancellationToken CancellationToken} and the per-call
 * deadline.
 *
 * <p>Tools that perform long-running I/O should poll {@code ctx.cancellation()} at safe points, or
 * block only in calls that end on interruption, so that the call's timeout, an {@code
 * AgentSession.interrupt(...)} or session close stops them. Tools that complete in a single fast
 * step can ignore the context entirely. A tool that does neither keeps running after its call has
 * been answered: Java offers no safe way to stop arbitrary code, so the dispatcher can only cancel,
 * interrupt and wait for it.
 */
@FunctionalInterface
public interface ToolExecutor {

  /**
   * Execute the tool with the given arguments and per-invocation context.
   *
   * @param arguments the arguments from the model; non-null
   * @param context the per-call context (cancellation + deadline); non-null
   * @return the result; non-null
   */
  ToolResult execute(Map<String, Object> arguments, ToolContext context);
}
