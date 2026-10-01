/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.tools;

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
  DELEGATION
}
