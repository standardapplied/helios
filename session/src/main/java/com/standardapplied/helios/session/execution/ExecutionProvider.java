/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.execution;

import com.standardapplied.helios.core.model.CancellationToken;
import com.standardapplied.helios.core.tool.SessionContext;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;

/**
 * Dispatch surface the {@code Execute} tool calls into to actually run a request. A session built
 * with {@code SessionOptions.Builder.withExecutionProvider(provider)} registers {@code Execute}
 * over it; a session without one has no {@code Execute} tool. Implementations range from
 * "host-process subprocess with a {@code CommandGrant} per runtime" ({@link
 * LocalProcessExecutionProvider}) to "spawn an Incus instance, run the script, dispose the
 * instance" ({@code IncusExecutionProvider}, ships separately) and "wrap a persistent JShell REPL"
 * ({@code JShellExecutionProvider} in {@code helios-repl}).
 *
 * <p>One {@code Execute} tool dispatches every runtime — providers route by {@link
 * ExecutionRequest#runtime()}. A provider declares its runtimes through {@link
 * #supportedRuntimes()}; the {@code Execute} tool refuses a request for any other runtime before
 * calling {@link #execute}. Each provider enforces its own isolation and clamps a request's timeout
 * to its own maximum.
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
 * close). Refusal fails the session's open — {@code AgentSession.open} throws {@link
 * com.standardapplied.helios.session.SessionUnavailableException}.
 *
 * <h2>Dispatch contract</h2>
 *
 * {@link #execute} runs on the calling thread, the tool call's own, and returns its result. It must
 * stop its work, and return or throw, when that thread is interrupted or the call's {@link
 * CancellationToken} fires; it then throws {@link CancellationException}, keeping the thread's
 * interrupt status. A provider that waits on a future of its own waits with {@link Future#get()},
 * never {@link java.util.concurrent.CompletableFuture#join()}, which ignores interrupts. A provider
 * that ignores both holds the tool call past its stop grace: the call is reported {@code
 * ToolOutcome.Unknown} and the session refuses later tool calls until it ends.
 *
 * <p>Implementations are non-sealed: customers will plug their own (Incus, custom remote runners),
 * so the surface stays open for extension.
 *
 * <h2>Thread-safety</h2>
 *
 * Providers must be safe for concurrent calls; one provider may serve many sessions at once. The
 * lifecycle methods are invoked once per session each; multiple sessions may invoke them
 * concurrently against a shared provider.
 */
public interface ExecutionProvider {

  /**
   * The runtimes this provider runs. Stable for the lifetime of the provider; the {@code Execute}
   * tool refuses any other runtime before calling {@link #execute}.
   *
   * @return non-null set of runtimes
   */
  Set<Runtime> supportedRuntimes();

  /**
   * Called once per session, before the agent loop starts. The provider may use this to allocate
   * per-session resources (a JShell REPL, an Incus instance, a connection), keyed by {@link
   * SessionContext#sessionId()}.
   *
   * <p>Defaults to {@link SessionStartOutcome#accept()} — stateless providers (a local subprocess
   * fork) need no per-session bookkeeping.
   *
   * <p>If the returned outcome is a {@link SessionStartOutcome.Refuse}, the session never opens:
   * {@code AgentSession.open} throws {@link
   * com.standardapplied.helios.session.SessionUnavailableException}. Use {@code Refuse} for
   * transient "cannot accept right now" cases (pool saturated, rate-limited); throw a {@link
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
   * Execute one request on the calling thread. Returns a non-null {@link ExecutionResult} for every
   * handled outcome (success, non-zero exit, timeout, refusal). Throws a {@link RuntimeException}
   * only when the provider itself is broken (binary missing, closed provider); the {@code Execute}
   * tool reports it as a tool failure.
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
   * @return the result
   * @throws CancellationException when the calling thread is interrupted or {@code cancellation}
   *     fires; the thread's interrupt status is kept
   */
  ExecutionResult execute(
      SessionContext session, ExecutionRequest request, CancellationToken cancellation);
}
