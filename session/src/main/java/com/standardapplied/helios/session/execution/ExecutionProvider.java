/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.runtime.SessionContext;
import java.util.concurrent.CompletionStage;

/**
 * Dispatch surface the {@code Execute} tool calls into to actually run a request. Implementations
 * range from "refuses everything" ({@link NoopExecutionProvider}) through "host-process subprocess
 * with a {@code CommandGrant} per runtime" ({@link LocalProcessExecutionProvider}) to "spawn an
 * Incus instance, run the script, dispose the instance" ({@code IncusExecutionProvider}, ships
 * separately) and "wrap a persistent JShell REPL" ({@code JShellExecutionProvider} in {@code
 * helios-repl}).
 *
 * <p>One {@code Execute} tool dispatches every runtime — providers route by {@link
 * ExecutionRequest#runtime()}. A provider must declare its supported runtimes via {@link
 * #capabilities()}; the {@code Execute} tool refuses requests for unsupported runtimes before
 * calling {@link #execute}.
 *
 * <h2>Session lifecycle</h2>
 *
 * The agent loop calls {@link #onSessionStart} once per session, before the loop's first iteration,
 * and {@link #onSessionEnd} once per session, after the loop terminates (success, cancel, error —
 * every path). Providers that pool per-session state (a JShell REPL per session, a warm Incus
 * instance per session, a remote-API connection per session) use {@link SessionContext#sessionId()}
 * as the routing key and tear down on session end.
 *
 * <p>{@link #onSessionStart} returns a {@link SessionStartOutcome}: {@link
 * SessionStartOutcome#accept()} for the common case, {@link SessionStartOutcome#refuse(String)
 * Refuse} when the provider cannot accept the session (pool saturated, auth failed, in-flight
 * close). Refusal short-circuits the loop — the session's terminal is {@link
 * ai.singlr.session.ResultMessage.ErrorProviderUnavailable}.
 *
 * <h2>Dispatch contract</h2>
 *
 * {@link #execute} is asynchronous so providers that block on I/O (Incus boot, JDBC, subprocess
 * wait) need not pin a thread. The returned {@link CompletionStage} must complete with a non-null
 * {@link ExecutionResult}; exceptional completion is acceptable for unrecoverable provider errors
 * (e.g. the binary isn't installed) and the {@code Execute} tool surfaces them as tool-failures.
 * The {@link CancellationToken} is cooperative — providers should call {@link
 * CancellationToken#throwIfCancelled()} at safe points (before fork, between drain rounds) so a
 * session shutdown halts the in-flight work promptly.
 *
 * <p>Implementations are non-sealed: customers will plug their own (Incus, custom remote runners),
 * so the surface stays open for extension.
 *
 * <h2>Thread-safety</h2>
 *
 * Providers must be safe for concurrent calls — the session loop is fully serial today, but the
 * surrounding {@link ai.singlr.session.tools.ToolCategory#EXECUTION EXECUTION} category has its own
 * dedicated concurrency cap in {@link ai.singlr.session.ConcurrencyLimits}, and future loops may
 * dispatch parallel execute tool calls. The lifecycle methods are invoked once per session each;
 * multiple sessions may invoke them concurrently against a shared provider.
 */
public interface ExecutionProvider {

  /**
   * Capabilities advertised by this provider. Stable for the lifetime of the provider — providers
   * should compute this once at construction and return a constant.
   *
   * @return non-null capabilities record
   */
  ExecutionCapabilities capabilities();

  /**
   * Called once per session, before the agent loop starts. The provider may use this to allocate
   * per-session resources (a JShell REPL, an Incus instance, a connection), keyed by {@link
   * SessionContext#sessionId()}.
   *
   * <p>Defaults to {@link SessionStartOutcome#accept()} — stateless providers (Noop, local
   * subprocess fork) need no per-session bookkeeping.
   *
   * <p>If the returned outcome is a {@link SessionStartOutcome.Refuse}, the agent loop never
   * starts; the session terminates immediately with {@link
   * ai.singlr.session.ResultMessage.ErrorProviderUnavailable}. Use {@code Refuse} for transient
   * "cannot accept right now" cases (pool saturated, rate-limited); throw a {@link
   * RuntimeException} only for genuinely unrecoverable misconfigurations.
   *
   * @param ctx session metadata; non-null
   * @return outcome describing whether the session is accepted
   */
  default SessionStartOutcome onSessionStart(SessionContext ctx) {
    return SessionStartOutcome.accept();
  }

  /**
   * Called once per session, after the agent loop terminates (any terminal — success, cancel,
   * error). Providers release per-session state here. Always fires for sessions that successfully
   * passed {@link #onSessionStart} (i.e. the provider returned {@link SessionStartOutcome.Accept}),
   * regardless of the loop's terminal cause.
   *
   * <p>Defaults to no-op.
   *
   * @param ctx the same session metadata previously passed to {@link #onSessionStart}; non-null
   */
  default void onSessionEnd(SessionContext ctx) {
    // Default: no-op.
  }

  /**
   * Execute one request. The returned future must complete with a non-null {@link ExecutionResult}
   * for any reasonably-handled outcome (success, non-zero exit, timeout, refused runtime). Throw
   * exceptionally only when the provider itself is broken (binary missing, internal misconfig); the
   * {@code Execute} tool surfaces those as tool failures.
   *
   * @param session the session this call belongs to; the same {@link SessionContext} previously
   *     passed to {@link #onSessionStart}. Providers route per-session state by {@link
   *     SessionContext#sessionId()}. Non-null
   * @param request the request to execute; non-null
   * @param cancellation cooperative cancellation token for this individual call. Distinct from
   *     {@link SessionContext#cancellation()} — the per-call token fires when this single
   *     invocation is cancelled (e.g. tool-dispatch timeout); the session token fires when the
   *     entire session is shutting down. Most providers register cleanup against the session token
   *     in {@link #onSessionStart} and the call token here in {@link #execute}. Non-null
   * @return a completion stage that yields the result
   */
  CompletionStage<ExecutionResult> execute(
      SessionContext session, ExecutionRequest request, CancellationToken cancellation);
}
