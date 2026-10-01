/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

/**
 * Controls how {@code Agent.resume(...)} handles a non-idempotent tool call that was {@link
 * ToolCallStatus#STARTED} but never reached a terminal status — typically because the JVM crashed
 * mid-call or the operation timed out.
 *
 * <ul>
 *   <li>{@link #FAIL_LOUD} (default) — the resume aborts with an {@link UnsafeResumeException}
 *       naming the in-flight call. The run stays {@link AgentRunStatus#SUSPENDED}; a human or
 *       higher-level system decides whether the side effect actually occurred and how to proceed.
 *   <li>{@link #AUTO_FAIL_AND_CONTINUE} — the journal entry is marked {@link ToolCallStatus#FAILED}
 *       with a synthetic "resume after crash; outcome unknown" error, the model receives a failure
 *       {@link ai.singlr.core.tool.ToolResult}, and the loop continues. Lossy if the tool actually
 *       succeeded server-side, but trajectory progresses without human intervention.
 * </ul>
 */
public enum UnsafeResumePolicy {
  FAIL_LOUD,
  AUTO_FAIL_AND_CONTINUE
}
