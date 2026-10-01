/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import ai.singlr.core.model.ToolCall;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.runtime.SessionContext;
import ai.singlr.core.tool.ToolContext;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.ConcurrencyLimits;
import ai.singlr.session.tools.ToolCategory;
import ai.singlr.session.tools.ToolRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Synchronous tool dispatch surface. The agent loop calls {@link #dispatch(ToolCall,
 * CancellationToken, Duration)} once per tool call the model emits; the dispatcher looks the call
 * up in its {@link ToolRegistry}, acquires the per-{@link ToolCategory category} semaphore
 * (blocking the calling virtual thread when the cap is full), runs the tool, and returns its {@link
 * ToolResult}.
 *
 * <p>{@link ToolCategory#WRITE} tool calls acquire from the {@code fileWritePermits} pool; {@link
 * ToolCategory#EXECUTION} calls acquire from the {@code executionPermits} pool; every other
 * category acquires from the general {@code toolCallPermits} pool. Sizes come from {@link
 * ConcurrencyLimits}.
 *
 * <p>Unknown tool names return a synthetic {@link ToolResult#failure(String) failure}; the agent
 * loop feeds that result back to the model so it can self-correct rather than crash.
 *
 * <h2>Thread-safety</h2>
 *
 * Immutable apart from the semaphore counters. Safe to share across the agent loop's threads.
 */
public final class ToolDispatch {

  private final SessionContext sessionContext;
  private final ToolRegistry registry;
  private final ConcurrencyLimits limits;
  private final Semaphore toolCallPermits;
  private final Semaphore fileWritePermits;
  private final Semaphore executionPermits;

  /**
   * Build a dispatcher.
   *
   * @param sessionContext per-session metadata stamped on every {@link ToolContext} built by this
   *     dispatcher; non-null
   * @param registry the bindings the dispatcher will look calls up against; non-null
   * @param limits concurrency caps; non-null
   * @throws NullPointerException if any argument is null
   */
  public ToolDispatch(
      SessionContext sessionContext, ToolRegistry registry, ConcurrencyLimits limits) {
    this.sessionContext = Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.limits = Objects.requireNonNull(limits, "limits must not be null");
    this.toolCallPermits = new Semaphore(limits.maxConcurrentToolCalls(), true);
    this.fileWritePermits = new Semaphore(limits.maxConcurrentFileWrites(), true);
    this.executionPermits = new Semaphore(limits.maxConcurrentExecutions(), true);
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
   * Dispatch one tool call. Blocks the calling thread until the appropriate semaphore is available,
   * then runs the tool on a fresh virtual thread bounded by {@code timeout}. Returns the tool's
   * result, a synthetic failure for unknown tools, or a synthetic failure when the timeout fires.
   * Cancellation is observed before acquire and re-thrown if signalled during the acquire wait;
   * after dispatch, cancellation flows through the {@link ToolContext} so tool implementations can
   * poll {@code ctx.cancellation()} at safe points.
   *
   * <p>On timeout the virtual thread carrying the tool keeps running but its result is discarded.
   * The dispatcher cancels the per-call view of the cancellation token first, so cooperative tools
   * stop promptly; misbehaving tools eventually finish on their own without blocking the loop.
   *
   * @param call the call; non-null
   * @param cancellation cooperative cancellation token; non-null
   * @param timeout per-call wall-clock budget; non-null, non-negative
   * @return the tool's result, or a synthetic failure on unknown tool / timeout / thrown exception
   * @throws NullPointerException if any argument is null
   * @throws CancellationException if cancellation fires before or during the permit wait
   */
  public ToolResult dispatch(ToolCall call, CancellationToken cancellation, Duration timeout) {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative, got " + timeout);
    }
    cancellation.throwIfCancelled();

    var bindingOpt = registry.get(call.name());
    if (bindingOpt.isEmpty()) {
      return ToolResult.failure("tool not found: " + call.name());
    }
    var binding = bindingOpt.orElseThrow();
    var permits = permitsFor(binding.category());
    try {
      permits.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancellationException("interrupted while acquiring permit for " + call.name());
    }
    try {
      var ctx = ToolContext.of(sessionContext, timeout);
      var future = new CompletableFuture<ToolResult>();
      var worker =
          Thread.ofVirtual()
              .name("helios-tool-" + call.name())
              .start(
                  () -> {
                    try {
                      future.complete(binding.tool().execute(call.arguments(), ctx));
                    } catch (Throwable t) {
                      future.completeExceptionally(t);
                    }
                  });
      try {
        return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        worker.interrupt();
        return ToolResult.failure("tool '" + call.name() + "' timed out after " + timeout);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        worker.interrupt();
        throw new CancellationException("interrupted waiting for tool " + call.name());
      } catch (ExecutionException e) {
        var cause = e.getCause();
        var msg =
            cause == null || cause.getMessage() == null
                ? (cause == null ? "no cause" : cause.getClass().getSimpleName())
                : cause.getMessage();
        return ToolResult.failure("tool '" + call.name() + "' threw: " + msg);
      }
    } finally {
      permits.release();
    }
  }

  private Semaphore permitsFor(ToolCategory category) {
    return switch (category) {
      case WRITE -> fileWritePermits;
      case EXECUTION -> executionPermits;
      case READ, SEARCH, CONTROL, NETWORK, DELEGATION -> toolCallPermits;
    };
  }
}
