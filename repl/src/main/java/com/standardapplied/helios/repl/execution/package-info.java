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
 * <p>This is the v2 reshape of the v1 {@code CodeExecutionTool} / {@code ReplSession} surface
 * called out in spec §6 Phase 6. The underlying {@link
 * com.standardapplied.helios.repl.sandbox.JvmSandbox} / {@link
 * com.standardapplied.helios.repl.sandbox.JvmSandboxBootstrap} / {@link
 * com.standardapplied.helios.repl.ReplSession} substrate is unchanged; this package adds the {@code
 * ExecutionProvider} adapter and lifecycle wiring so {@code Runtime.JSHELL} flows through the v2
 * {@code Execute} tool.
 */
package com.standardapplied.helios.repl.execution;
