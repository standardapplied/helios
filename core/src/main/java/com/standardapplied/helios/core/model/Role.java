/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.model;

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
