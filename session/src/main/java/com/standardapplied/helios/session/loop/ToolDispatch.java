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
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Synchronous tool dispatch surface. The agent loop calls {@link #dispatch(ToolCall,
 * CancellationToken, Duration, Duration)} once per tool call the model emits; the dispatcher looks
 * the call up in its {@link ToolRegistry} and runs it as a {@link DispatchedCall}: a virtual thread
 * that takes the per-{@link ToolCategory category} permit, runs the tool and gives the permit back
 * when the tool has returned.
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
 * {@link ToolOutcome.Unknown}. {@link #liveCalls()} counts them.
 *
 * <h2>Thread-safety</h2>
 *
 * Safe to share across the agent loop's threads: the permit pools are semaphores and the count of
 * live calls is atomic.
 */
public final class ToolDispatch {

  private final SessionContext sessionContext;
  private final ToolRegistry registry;
  private final ConcurrencyLimits limits;
  private final Semaphore toolCallPermits;
  private final Semaphore fileWritePermits;
  private final Semaphore executionPermits;
  private final ScheduledExecutorService scheduler;
  private final AtomicInteger liveCalls = new AtomicInteger();

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
    this.toolCallPermits = new Semaphore(limits.maxConcurrentToolCalls(), true);
    this.fileWritePermits = new Semaphore(limits.maxConcurrentFileWrites(), true);
    this.executionPermits = new Semaphore(limits.maxConcurrentExecutions(), true);
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
   * Available permits for general tool calls.
   *
   * @return non-negative count
   */
  public int availableToolCallPermits() {
    return toolCallPermits.availablePermits();
  }

  /**
   * Available permits for file writes.
   *
   * @return non-negative count
   */
  public int availableFileWritePermits() {
    return fileWritePermits.availablePermits();
  }

  /**
   * Available permits for code executions.
   *
   * @return non-negative count
   */
  public int availableExecutionPermits() {
    return executionPermits.availablePermits();
  }

  /**
   * Dispatches blocked waiting for a permit of {@code category}'s pool. Lets a test wait until a
   * dispatch has queued behind a saturated pool before it acts.
   *
   * @return non-negative count
   */
  int queuedDispatches(ToolCategory category) {
    return permitsFor(category).getQueueLength();
  }

  /**
   * Calls whose worker has not ended yet, including calls already answered as {@link
   * ToolOutcome.Unknown}.
   *
   * @return non-negative count
   */
  int liveCalls() {
    return liveCalls.get();
  }

  /**
   * Dispatch one tool call and wait for its answer. The call gets its own cancellation token,
   * linked to {@code cancellation}, and one deadline, {@code timeout} from now, that covers both
   * the wait for a permit and the tool's run. Returns the tool's result, a not-found result for an
   * unknown tool, or, when the deadline passes:
   *
   * <ul>
   *   <li>{@link ToolOutcome.TimedOut} if the tool never started (it never will) or if, once the
   *       call's token is cancelled and its worker interrupted, the tool stops within {@code
   *       stopGrace};
   *   <li>{@link ToolOutcome.Unknown} if it is still running after {@code stopGrace}. Java offers
   *       no safe way to stop arbitrary code, so it runs on; its permit stays taken until it ends,
   *       so a later call of the same category waits for it, and its result, if it ever returns, is
   *       discarded.
   * </ul>
   *
   * <p>A tool that throws an {@link Error} makes this method throw that same error.
   *
   * @param call the call; non-null
   * @param cancellation the session's cancellation token; non-null
   * @param timeout per-call budget, from now; non-null, positive
   * @param stopGrace how long to wait for a timed-out tool to stop; non-null, positive
   * @return the tool's result, or a framework result on unknown tool or timeout
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code timeout} or {@code stopGrace} is not positive
   * @throws CancellationException if {@code cancellation} fires or the calling thread is
   *     interrupted (its interrupt status is kept) before the call is answered
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
            binding.orElseThrow().tool(),
            call,
            sessionContext,
            timeout,
            permitsFor(binding.orElseThrow().category()),
            liveCalls);
    dispatched.start(cancellation, scheduler);
    return dispatched.await(stopGrace);
  }

  private Semaphore permitsFor(ToolCategory category) {
    return switch (category) {
      case WRITE -> fileWritePermits;
      case EXECUTION -> executionPermits;
      case READ, SEARCH, CONTROL, NETWORK, DELEGATION -> toolCallPermits;
    };
  }
}
