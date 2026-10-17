/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.session.execution.ExecutionCapabilities;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.execution.ExecutionRequest;
import com.standardapplied.helios.session.execution.ExecutionResult;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Records the session start and end it sees, and does what the test sets for each: by default it
 * accepts, and its end appends {@code "onSessionEnd"} to the order it was given.
 */
final class RecordingProvider implements ExecutionProvider {

  final AtomicInteger starts = new AtomicInteger();
  final AtomicInteger ends = new AtomicInteger();
  volatile SessionContext context;
  Supplier<SessionStartOutcome> start = SessionStartOutcome::accept;
  Runnable end;

  RecordingProvider(List<String> order) {
    this.end = () -> order.add("onSessionEnd");
  }

  /** Blocks until {@code latch} opens, as an extension that ignores interruption does. */
  static void blockIgnoringInterrupts(CountDownLatch latch) {
    var interrupted = false;
    while (latch.getCount() > 0) {
      try {
        latch.await();
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public ExecutionCapabilities capabilities() {
    return ExecutionCapabilities.newBuilder().build();
  }

  @Override
  public SessionStartOutcome onSessionStart(SessionContext ctx) {
    starts.incrementAndGet();
    context = ctx;
    return start.get();
  }

  @Override
  public void onSessionEnd(SessionContext ctx) {
    ends.incrementAndGet();
    end.run();
  }

  @Override
  public CompletionStage<ExecutionResult> execute(
      SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
    throw new UnsupportedOperationException("not used");
  }
}
