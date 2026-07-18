/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

/** Reason why the model stopped generating. */
public enum FinishReason {
  /** Model finished naturally. */
  STOP,

  /** Model wants to call tools. */
  TOOL_CALLS,

  /** Hit maximum token limit. */
  LENGTH,

  /** Content was filtered. */
  CONTENT_FILTER,

  /**
   * The provider's safety system declined the request before or during generation (e.g. Anthropic's
   * {@code stop_reason: "refusal"} on Claude Fable 5+). Content may be empty (declined pre-output)
   * or partial (declined mid-stream). Distinct from {@link #CONTENT_FILTER}, which reflects
   * post-hoc filtering of generated content.
   */
  REFUSAL,

  /** An error occurred. */
  ERROR
}
