/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * Execution providers — the surface the {@code Execute} tool dispatches through to run
 * user-supplied code in a controlled environment. The {@link
 * com.standardapplied.helios.session.execution.Runtime} enum names the language / shell flavour; an
 * {@link com.standardapplied.helios.session.execution.ExecutionProvider} declares which runtimes it
 * supports and executes a request on the calling thread, returning an {@link
 * com.standardapplied.helios.session.execution.ExecutionResult} with stdout, stderr, exit code, and
 * a {@code timedOut} flag.
 *
 * <p>A session runs code only when built with {@code
 * SessionOptions.Builder.withExecutionProvider(provider)}: it then registers the {@code Execute}
 * tool over that provider and runs the provider's {@code onSessionStart} and {@code onSessionEnd}
 * with the session; without one it has no {@code Execute} tool. The built-in provider is {@code
 * LocalProcessExecutionProvider} (each runtime run through {@link
 * com.standardapplied.helios.core.process.BoundedProcess} with argv-only invocation, a cleared
 * environment, capped output and descendant kill on timeout; the session redacts its output).
 * Library users can plug their own — e.g. an Incus-backed provider for library-mode use where
 * execution must happen inside a sandbox rather than the host process.
 *
 * <p>The {@code Execute} tool itself is a single tool that dispatches every runtime through the
 * session's provider, classified under {@link
 * com.standardapplied.helios.session.tools.ToolCategory#EXECUTION} so permission rules and audit
 * attribution treat it uniformly. {@code Runtime.JSHELL} is served by {@code helios-repl}'s {@code
 * JShellExecutionProvider}.
 *
 * <p>Spec: {@code docs/specs/agentic-coding-sdk-java-v2.md} §11.
 */
package com.standardapplied.helios.session.execution;
