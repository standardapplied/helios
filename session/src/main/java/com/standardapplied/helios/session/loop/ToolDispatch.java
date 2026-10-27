/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.Validate;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Synchronous tool dispatch surface. The agent loop calls {@link #dispatch(ToolCall,
 * CancellationToken, Duration, Duration, Predicate)} once per tool call the model emits, one call
 * at a time; the dispatcher looks the call up in its {@link ToolRegistry} and runs it as a {@link
 * DispatchedCall} on a virtual thread of its own.
 *
 * <p>Unknown tool names return a {@link ToolResult#notFound(String) not-found} result; the agent
 * loop feeds that result back to the model so it can self-correct rather than crash.
 *
 * <p>Each call stays linked to the session's cancellation token until its worker ends, so closing
 * the session cancels and interrupts every call still running, including one already answered as
 * {@link ToolOutcome.Unknown}. {@link #awaitLiveCalls(long)} waits for them to end.
 *
 * <h2>Thread-safety</h2>
 *
 * The agent loop dispatches one call at a time; {@link #awaitLiveCalls(long)} may run on another
 * thread, since the set of live calls is concurrent.
 */
public final class ToolDispatch {

  private final SessionContext sessionContext;
  private final ToolRegistry registry;
  private final Set<DispatchedCall> liveCalls = ConcurrentHashMap.newKeySet();
  private final SessionEnding ending;
  private volatile DispatchedCall abandoned;

  /** {@code ending} records every {@link Error} the work of a call throws. */
  public ToolDispatch(SessionContext sessionContext, ToolRegistry registry, SessionEnding ending) {
    this.sessionContext = sessionContext;
    this.registry = registry;
    this.ending = ending;
  }

  /**
   * The session context this dispatcher stamps on every {@link ToolContext}.
   *
   * @return non-null session context
   */
  public SessionContext sessionContext() {
    return sessionContext;
  }

  /**
   * The bound tool registry.
   *
   * @return non-null registry
   */
  public ToolRegistry registry() {
    return registry;
  }

  /**
   * Wait until {@code deadlineNanos}, a {@link System#nanoTime()} instant, for the work of every
   * call still running to end: the calls whose worker or token cancellation has not ended,
   * including calls answered as {@link ToolOutcome.Unknown}. Waiting cancels nothing; the session
   * ends its calls by cancelling its token first. An interrupt of the waiting thread ends the wait,
   * its interrupt status kept.
   *
   * @param deadlineNanos when to stop waiting
   * @return the calls whose work had not ended when the wait ended, empty when none
   */
  public List<ToolCall> awaitLiveCalls(long deadlineNanos) {
    return List.copyOf(liveCalls).stream()
        .filter(live -> !live.endsBy(deadlineNanos))
        .map(DispatchedCall::call)
        .toList();
  }

  /** Settle every live call's answer as its deadline does, for tests that fire deadlines. */
  void expireLiveCalls() {
    liveCalls.forEach(DispatchedCall::expire);
  }

  /**
   * Dispatch one tool call and wait for its answer. The call runs on its own cancellation token,
   * linked to {@code cancellation}, under one deadline, {@code timeout} from now, that covers the
   * tool's start and run; past it, this method waits at most {@code stopGrace}. A tool not started
   * by the deadline, or before {@code cancellation} fires, never starts. At the deadline the call's
   * token is cancelled and its worker interrupted, and the call is:
   *
   * <ul>
   *   <li>{@link ToolOutcome.TimedOut} if the tool never started or stopped within {@code
   *       stopGrace};
   *   <li>{@link ToolOutcome.Unknown} if it still runs. Java cannot safely stop arbitrary code, so
   *       it runs on until it ends.
   * </ul>
   *
   * <p>A call is abandoned when this method returns or throws while its work still runs: answered
   * {@link ToolOutcome.Unknown}, or left on {@code cancellation} or on the calling thread's
   * interrupt. While an abandoned call's work has not ended, every new call, of any category, is
   * {@link ToolOutcome.Rejected rejected} by {@code ToolDispatch} at once, its tool never started,
   * with the reason {@code tool '<name>' did not stop; no tool runs until it ends}, so a session
   * leaks at most one call. The call's token is cancelled on a thread of its own, so a callback
   * that blocks or throws holds up neither this method, the session's cancellation nor the
   * interrupt. A tool's {@link Error} is rethrown as itself.
   *
   * @param call the call; non-null
   * @param cancellation the session's cancellation token; non-null
   * @param timeout per-call budget, from now; non-null, positive
   * @param stopGrace how long to wait for a timed-out tool to stop; non-null, positive
   * @param withheld true for the canonical key of an entry the call must not return, handed to the
   *     tool through its {@link ToolContext}; non-null
   * @return the tool's result, or a framework result on unknown tool, refusal or timeout
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code timeout} or {@code stopGrace} is not positive
   * @throws Error the error the tool threw
   * @throws CancellationException if {@code cancellation} fires or the calling thread is
   *     interrupted (its interrupt status is kept) before this method returns a result
   */
  public ToolResult dispatch(
      ToolCall call,
      CancellationToken cancellation,
      Duration timeout,
      Duration stopGrace,
      Predicate<String> withheld) {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    Validate.positiveDuration("timeout", timeout);
    Validate.positiveDuration("stopGrace", stopGrace);
    Objects.requireNonNull(withheld, "withheld must not be null");
    cancellation.throwIfCancelled();

    var binding = registry.get(call.name());
    if (binding.isEmpty()) {
      return ToolResult.notFound(call.name());
    }
    var stuck = abandoned;
    if (stuck != null && stuck.isRunning()) {
      return ToolResult.rejected(
          ToolDispatch.class.getSimpleName(),
          "tool '" + stuck.call().name() + "' did not stop; no tool runs until it ends");
    }
    var dispatched =
        new DispatchedCall(
            binding.orElseThrow(),
            call,
            sessionContext,
            cancellation,
            timeout,
            withheld,
            liveCalls,
            left -> abandoned = left,
            ending);
    dispatched.start();
    return dispatched.await(stopGrace);
  }
}
