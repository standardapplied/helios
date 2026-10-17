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
import com.standardapplied.helios.session.ConcurrencyLimits;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Synchronous tool dispatch surface. The agent loop calls {@link #dispatch(ToolCall,
 * CancellationToken, Duration, Duration)} once per tool call the model emits; the dispatcher looks
 * the call up in its {@link ToolRegistry} and runs it as a {@link DispatchedCall}: a virtual thread
 * admitted by the per-{@link ToolCategory category} {@link CategoryPermits}, which runs the tool,
 * and the permit is given back when the call's work has ended.
 *
 * <p>{@link ToolCategory#WRITE} tool calls acquire from the {@code fileWritePermits} pool; {@link
 * ToolCategory#EXECUTION} calls acquire from the {@code executionPermits} pool; every other
 * category acquires from the general {@code toolCallPermits} pool. Sizes come from {@link
 * ConcurrencyLimits}.
 *
 * <p>Unknown tool names return a {@link ToolResult#notFound(String) not-found} result; the agent
 * loop feeds that result back to the model so it can self-correct rather than crash.
 *
 * <p>Each call stays linked to the session's cancellation token until its worker ends, so closing
 * the session cancels and interrupts every call still running, including one already answered as
 * {@link ToolOutcome.Unknown}. {@link #awaitLiveCalls(Duration)} waits for them to end.
 *
 * <h2>Thread-safety</h2>
 *
 * Safe to share across the agent loop's threads: the permit pools share one lock and the set of
 * live calls is concurrent.
 */
public final class ToolDispatch {

  private final SessionContext sessionContext;
  private final ToolRegistry registry;
  private final ConcurrencyLimits limits;
  private final CategoryPermits toolCallPermits;
  private final CategoryPermits fileWritePermits;
  private final CategoryPermits executionPermits;
  private final ScheduledExecutorService scheduler;
  private final Set<DispatchedCall> liveCalls = ConcurrentHashMap.newKeySet();

  /**
   * Build a dispatcher.
   *
   * @param sessionContext per-session metadata stamped on every {@link ToolContext} built by this
   *     dispatcher; non-null
   * @param registry the bindings the dispatcher will look calls up against; non-null
   * @param limits concurrency caps; non-null
   * @param scheduler the session's scheduler, on which each call's deadline runs; non-null
   * @throws NullPointerException if any argument is null
   */
  public ToolDispatch(
      SessionContext sessionContext,
      ToolRegistry registry,
      ConcurrencyLimits limits,
      ScheduledExecutorService scheduler) {
    this.sessionContext = Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.limits = Objects.requireNonNull(limits, "limits must not be null");
    var scope = new CategoryPermits.Scope();
    this.toolCallPermits = new CategoryPermits(limits.maxConcurrentToolCalls(), scope);
    this.fileWritePermits = new CategoryPermits(limits.maxConcurrentFileWrites(), scope);
    this.executionPermits = new CategoryPermits(limits.maxConcurrentExecutions(), scope);
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
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
   * The concurrency caps in force.
   *
   * @return the limits record
   */
  public ConcurrencyLimits limits() {
    return limits;
  }

  /**
   * Permits of the general pool not held by a call whose work is still running.
   *
   * @return non-negative count
   */
  public int availableToolCallPermits() {
    return toolCallPermits.available();
  }

  /**
   * Permits of the file-write pool not held by a call whose work is still running.
   *
   * @return non-negative count
   */
  public int availableFileWritePermits() {
    return fileWritePermits.available();
  }

  /**
   * Permits of the execution pool not held by a call whose work is still running.
   *
   * @return non-negative count
   */
  public int availableExecutionPermits() {
    return executionPermits.available();
  }

  /**
   * Dispatches blocked waiting for a permit of {@code category}'s pool. Lets a test wait until a
   * dispatch has queued behind a saturated pool before it acts.
   *
   * @return non-negative count
   */
  int queuedDispatches(ToolCategory category) {
    return permitsFor(category).queued();
  }

  /**
   * Calls whose work, the worker and any cancellation of the call's token, has not ended yet,
   * including calls already answered as {@link ToolOutcome.Unknown}.
   *
   * @return non-negative count
   */
  int liveCalls() {
    return liveCalls.size();
  }

  /**
   * Wait up to {@code grace} for the work of every call still running to end: the calls whose
   * worker or token cancellation has not ended, including calls answered as {@link
   * ToolOutcome.Unknown}. Waiting cancels nothing; the session ends its calls by cancelling its
   * token first. An interrupt of the waiting thread ends the wait, its interrupt status kept.
   *
   * @param grace how long to wait; non-null, positive
   * @return the calls whose work had not ended when the wait ended, empty when none
   * @throws NullPointerException if {@code grace} is null
   * @throws IllegalArgumentException if {@code grace} is not positive
   */
  public List<ToolCall> awaitLiveCalls(Duration grace) {
    Validate.positiveDuration("grace", grace);
    var until = System.nanoTime() + TimeUnit.NANOSECONDS.convert(grace);
    return List.copyOf(liveCalls).stream()
        .filter(call -> !call.endsBy(until))
        .map(DispatchedCall::call)
        .toList();
  }

  /**
   * Dispatch one tool call and wait for its answer. The call runs on its own cancellation token,
   * linked to {@code cancellation}, under one deadline, {@code timeout} from now, that covers the
   * wait for a permit and the tool's run; past it, this method waits at most {@code stopGrace}. A
   * tool not started by the deadline, or before {@code cancellation} fires, never starts. At the
   * deadline the call's token is cancelled and its worker interrupted, and the call is:
   *
   * <ul>
   *   <li>{@link ToolOutcome.TimedOut} if the tool never started or stopped within {@code
   *       stopGrace};
   *   <li>{@link ToolOutcome.Unknown} if it still runs. Java cannot safely stop arbitrary code, so
   *       it runs on and keeps its permit until it ends.
   * </ul>
   *
   * <p>While a call whose category {@link ToolCategory#hasSideEffects has side effects} still runs
   * after this method stopped waiting for it, at its deadline, on {@code cancellation} or on the
   * calling thread's interrupt, every other call, of any category and including one already queued,
   * is {@link ToolOutcome.Rejected rejected} by {@code ToolDispatch} at once, its tool never
   * started, with the reason {@code tool '<name>' did not stop; no tool runs until it ends}. A call
   * that only waits for room in its category still queues. The call's token is cancelled on a
   * thread of its own, so a callback that blocks or throws holds up neither this method, the
   * session's cancellation nor the interrupt. A tool's {@link Error} is rethrown as itself.
   *
   * @param call the call; non-null
   * @param cancellation the session's cancellation token; non-null
   * @param timeout per-call budget, from now; non-null, positive
   * @param stopGrace how long to wait for a timed-out tool to stop; non-null, positive
   * @return the tool's result, or a framework result on unknown tool or timeout
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code timeout} or {@code stopGrace} is not positive
   * @throws CancellationException if {@code cancellation} fires or the calling thread is
   *     interrupted (its interrupt status is kept) before this method returns a result
   */
  public ToolResult dispatch(
      ToolCall call, CancellationToken cancellation, Duration timeout, Duration stopGrace) {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    Validate.positiveDuration("timeout", timeout);
    Validate.positiveDuration("stopGrace", stopGrace);
    cancellation.throwIfCancelled();

    var binding = registry.get(call.name());
    if (binding.isEmpty()) {
      return ToolResult.notFound(call.name());
    }
    var dispatched =
        new DispatchedCall(
            binding.orElseThrow(),
            call,
            sessionContext,
            cancellation,
            timeout,
            permitsFor(binding.orElseThrow().category()),
            liveCalls);
    dispatched.start(scheduler);
    return dispatched.await(stopGrace);
  }

  private CategoryPermits permitsFor(ToolCategory category) {
    return switch (category) {
      case WRITE -> fileWritePermits;
      case EXECUTION -> executionPermits;
      case READ, SEARCH, CONTROL, NETWORK, DELEGATION -> toolCallPermits;
    };
  }
}
