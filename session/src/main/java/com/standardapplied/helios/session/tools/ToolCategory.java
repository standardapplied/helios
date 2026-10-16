/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.tools;

/**
 * Classification axis for tools. Drives default permission rules, audit attribution, and the
 * visibility lattice (e.g. "block all WRITE tools in dry-run mode"). Each tool registers exactly
 * one category — see spec §8.2.
 */
public enum ToolCategory {
  /** File reads, directory listing, content search ({@code Read}, {@code LS}, {@code Grep}). */
  READ,
  /** File writes and edits ({@code Write}, {@code Edit}, {@code MultiEdit}, memory writes). */
  WRITE,
  /** Domain-specific search tools (knowledge base, web search). */
  SEARCH,
  /** Code execution dispatched through an {@code ExecutionProvider}. */
  EXECUTION,
  /** Interactive control: {@code AskUserQuestion}, signature gates, approval prompts. */
  CONTROL,
  /** Outbound network access — HTTP fetch, external API calls. */
  NETWORK,
  /** Sub-agent delegation tools (an Agent exposed via {@code asTool}). */
  DELEGATION;

  /**
   * Whether a call of this category may change state outside the call: every category but {@link
   * #READ} and {@link #SEARCH}. A call with side effects that still runs after its caller stopped
   * waiting keeps the dispatcher from running any other call until it ends.
   *
   * @return {@code true} if a call of this category may have side effects
   */
  public boolean hasSideEffects() {
    return switch (this) {
      case READ, SEARCH -> false;
      case WRITE, EXECUTION, CONTROL, NETWORK, DELEGATION -> true;
    };
  }
}
