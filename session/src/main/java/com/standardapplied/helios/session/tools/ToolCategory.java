/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.tools;

/**
 * The kind of effect a tool has, which sets the permission policy's default for its calls when no
 * rule decides them. Each tool registers exactly one category.
 */
public enum ToolCategory {
  /** File reads, directory listing and content search ({@code Read}, {@code LS}, {@code Grep}). */
  READ,
  /** File writes and edits, memory writes included. */
  WRITE,
  /** Code execution dispatched through an {@code ExecutionProvider}. */
  EXECUTION,
  /** Outbound network access: HTTP fetch, external API calls. */
  NETWORK,
  /** Interaction with the session itself, such as {@code AskUserQuestion}. */
  CONTROL
}
