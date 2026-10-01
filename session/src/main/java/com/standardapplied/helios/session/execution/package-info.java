/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * Execution providers — the surface the {@code Execute} tool dispatches through to run
 * user-supplied code in a controlled environment. The {@link ai.singlr.session.execution.Runtime}
 * enum names the language / shell flavour; an {@link ai.singlr.session.execution.ExecutionProvider}
 * declares which runtimes it supports and executes a request asynchronously, returning an {@link
 * ai.singlr.session.execution.ExecutionResult} with stdout, stderr, exit code, and a {@code
 * timedOut} flag.
 *
 * <p>Two built-in providers ship with Helios: {@link
 * ai.singlr.session.execution.NoopExecutionProvider} (the safe default — refuses every runtime so a
 * misconfigured session cannot silently shell out) and {@code LocalProcessExecutionProvider} (each
 * runtime backed by a {@link ai.singlr.core.tool.CommandGrant} with secret redaction, argv-only
 * invocation, env-only secret transport, descendant kill on timeout). Library users can plug their
 * own — e.g. an Incus-backed provider for library-mode use where execution must happen inside a
 * sandbox rather than the host process.
 *
 * <p>The {@code Execute} tool itself is a single tool that dispatches every runtime through the
 * configured provider, classified under {@link ai.singlr.session.tools.ToolCategory#EXECUTION} so
 * permission rules and audit attribution treat it uniformly. The {@code Runtime.JSHELL} value is
 * declared but its handler lands in Phase 6 alongside the {@code CodeActPreset}.
 *
 * <p>Spec: {@code docs/specs/agentic-coding-sdk-java-v2.md} §11.
 */
package ai.singlr.session.execution;
