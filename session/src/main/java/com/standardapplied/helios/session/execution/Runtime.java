/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

/**
 * The language / shell flavour an {@link ExecutionProvider} dispatches against. A single {@code
 * Execute} tool routes by this enum, so adding a new runtime is a one-line enum addition followed
 * by a handler in the provider — not a new tool class. The enum's wire form is the constant name
 * ({@code "BASH"}, {@code "PYTHON"}, …) — provider implementations parse it from the model's tool
 * call arguments.
 *
 * <p>Spec: §11.1. Not all values are necessarily supported by every provider; a provider's {@link
 * ExecutionCapabilities#supportedRuntimes()} is the authoritative list at run time.
 */
public enum Runtime {
  /** POSIX shell — Bash 3.2+ semantics, single-script via {@code -c}. */
  BASH,
  /** Python 3 — script passed via {@code -c} or a temporary file. */
  PYTHON,
  /**
   * SQL via a provider-configured JDBC handler. Phase 5 declares the enum value but ships no
   * built-in handler — a follow-up slice adds the JDBC allowlist plumbing.
   */
  SQL,
  /**
   * JShell — JDK 25 Java REPL. The Phase 6 {@code CodeActPreset} wires this through the existing
   * {@code JvmSandbox} / {@code ReplSession} substrate; Phase 5 ships the enum value only.
   */
  JSHELL,
  /** R — for statistical / analytics workloads. No built-in handler in Phase 5. */
  R,
  /** Node.js — for JavaScript workloads. No built-in handler in Phase 5. */
  NODE,
  /**
   * Custom runtime — for provider-specific languages declared by name (e.g. {@code "groovy"},
   * {@code "deno"}). The provider is expected to interpret {@link ExecutionRequest#script()} as the
   * script body and look up its dispatcher by the script's discriminator.
   */
  CUSTOM
}
