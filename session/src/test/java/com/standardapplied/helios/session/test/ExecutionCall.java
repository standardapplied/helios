/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.test;

import com.standardapplied.helios.core.model.CancellationToken;
import com.standardapplied.helios.core.tool.SessionContext;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.execution.ExecutionRequest;
import com.standardapplied.helios.session.execution.ExecutionResult;
import java.util.concurrent.CompletableFuture;

/**
 * One {@link ExecutionProvider#execute} run on a virtual thread of its own, as a tool call runs it,
 * so a test can wait on it through {@code Await}, cancel its token or interrupt its thread. The
 * outcome completes with the result, or with what {@code execute} threw; {@link
 * #interruptedAtEnd()} tells whether the thread's interrupt status was set when it ended.
 */
public final class ExecutionCall {

  private final CompletableFuture<ExecutionResult> outcome = new CompletableFuture<>();
  private final Thread thread;
  private volatile boolean interruptedAtEnd;

  private ExecutionCall(
      ExecutionProvider provider,
      SessionContext session,
      ExecutionRequest request,
      CancellationToken token) {
    this.thread = Thread.ofVirtual().unstarted(() -> run(provider, session, request, token));
  }

  /** Starts {@code provider.execute(session, request, token)} on a new virtual thread. */
  public static ExecutionCall start(
      ExecutionProvider provider,
      SessionContext session,
      ExecutionRequest request,
      CancellationToken token) {
    var call = new ExecutionCall(provider, session, request, token);
    call.thread.start();
    return call;
  }

  /** What {@code execute} returned or threw. */
  public CompletableFuture<ExecutionResult> outcome() {
    return outcome;
  }

  /** The thread {@code execute} runs on. */
  public Thread thread() {
    return thread;
  }

  /** Whether the thread's interrupt status was set when {@code execute} returned or threw. */
  public boolean interruptedAtEnd() {
    return interruptedAtEnd;
  }

  private void run(
      ExecutionProvider provider,
      SessionContext session,
      ExecutionRequest request,
      CancellationToken token) {
    try {
      var result = provider.execute(session, request, token);
      interruptedAtEnd = Thread.currentThread().isInterrupted();
      outcome.complete(result);
    } catch (Throwable t) {
      interruptedAtEnd = Thread.currentThread().isInterrupted();
      outcome.completeExceptionally(t);
    }
  }
}
