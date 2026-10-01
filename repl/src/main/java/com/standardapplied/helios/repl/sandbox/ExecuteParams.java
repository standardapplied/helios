/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

/**
 * Per-call sandbox-execution overrides that are independent of the {@link ExecutionRequest} body.
 * Today this carries the bindings-snapshot caps; future per-call telemetry knobs can live here too
 * without expanding the {@link ExecutionRequest} record.
 *
 * <p>{@link ai.singlr.repl.ReplSession} builds one of these from the active {@link
 * ai.singlr.repl.ReplConfig}; standalone callers can stay on {@link #DEFAULT} or {@link #DISABLED}.
 *
 * @param captureBindings whether the sandbox should snapshot user-declared variables after this
 *     execute and return them in {@link ExecutionResult#bindings()}
 * @param maxBindingValueChars per-value cap on the {@code toString} repr in the snapshot; {@code 0}
 *     disables per-value truncation
 * @param maxBindingSnapshotChars total cap across all values in a single bindings snapshot; {@code
 *     0} disables the total cap
 */
public record ExecuteParams(
    boolean captureBindings, int maxBindingValueChars, int maxBindingSnapshotChars) {

  /** Sandbox-side defaults: capture, 200-char per-value cap, 16 KB snapshot cap. */
  public static final ExecuteParams DEFAULT = new ExecuteParams(true, 200, 16 * 1024);

  /** Disable binding capture entirely. */
  public static final ExecuteParams DISABLED = new ExecuteParams(false, 0, 0);
}
