/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import ai.singlr.repl.sandbox.ExecutionResult;
import java.util.Map;

/**
 * Observer for the sandbox's working-memory state after each {@code execute_code} call.
 *
 * <p>Where {@code EventSink} (in helios-core) gives lifecycle events at the agent-loop level, this
 * listener gives REPL-specific structural state: every user-declared {@code var} in the sandbox at
 * the moment the execute returned, mapped to a length-capped {@code toString} repr. Watching the
 * bindings stream is what makes "user watches the agent think" UX possible — a panel showing {@code
 * macro = "Fed in pause mode, VIX=18.5..."} lighting up as the model binds variables across
 * iterations. It also gives operators a debug record richer than truncated stdout when an output
 * goes wrong.
 *
 * <p>The bindings come from a {@link jdk.jshell.JShell#variables()} sweep on the sandbox side,
 * filtered to exclude harness-internal {@code __}-prefixed names and capped per-value (default 200
 * chars) and per-snapshot (default 16 KB) so a single 50KB {@code predict()} result doesn't blow
 * the protocol or your listener.
 *
 * <p>Listener contract: must be cheap and non-blocking — fires synchronously after every execute.
 * Exceptions are caught and ignored so a misbehaving listener doesn't break the run.
 *
 * <p>Wire via {@link ReplConfig.Builder#withSandboxBindingsListener(SandboxBindingsListener)}.
 */
@FunctionalInterface
public interface SandboxBindingsListener {

  /**
   * Called after each {@code execute_code} completes, with the bindings snapshot pulled from the
   * sandbox.
   *
   * @param bindings post-execute snapshot of user-declared sandbox variables; never {@code null},
   *     may be empty when nothing has been bound yet
   * @param result the full execution result (stdout, stderr, exit code, submitted value); useful
   *     for correlating bindings with the code that produced them
   */
  void onBindings(Map<String, String> bindings, ExecutionResult result);
}
