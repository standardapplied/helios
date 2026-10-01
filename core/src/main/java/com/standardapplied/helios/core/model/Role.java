/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

/** The role of a message in a conversation. */
public enum Role {
  /** System instructions for the model. */
  SYSTEM,

  /** Message from the user. */
  USER,

  /** Response from the assistant/model. */
  ASSISTANT,

  /** Result from a tool execution. */
  TOOL
}
