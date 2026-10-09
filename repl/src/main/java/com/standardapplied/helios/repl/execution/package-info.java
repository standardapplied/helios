/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * JShell-backed {@link com.standardapplied.helios.session.execution.ExecutionProvider} for the v2
 * session SDK. One persistent {@link com.standardapplied.helios.repl.ReplSession} per Helios
 * session, keyed by {@code sessionId}. Variables, imports, classes, and JIT state persist across
 * {@code Execute} tool calls within the same agent loop — the model can define {@code var x = ...}
 * in turn 1 and read {@code x} in turn 7. The persistent state is the value proposition: per-call
 * fork (what {@code LocalProcessExecutionProvider} does) loses it entirely.
 *
 * <p>This package adds the {@code ExecutionProvider} adapter and lifecycle wiring over the {@link
 * com.standardapplied.helios.repl.sandbox.JvmSandbox} / {@link
 * com.standardapplied.helios.repl.sandbox.JvmSandboxBootstrap} / {@link
 * com.standardapplied.helios.repl.ReplSession} substrate, so {@code Runtime.JSHELL} flows through
 * the {@code Execute} tool, the one way to run Java snippets.
 */
package com.standardapplied.helios.repl.execution;
