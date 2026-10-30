/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.model.CancellationToken;
import com.standardapplied.helios.core.tool.SessionContext;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.execution.ExecutionRequest;
import com.standardapplied.helios.session.execution.ExecutionResult;
import com.standardapplied.helios.session.execution.Runtime;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Records the session start and end it sees, and does what the test sets for each: by default it
 * accepts, and its end appends {@code "onSessionEnd"} to the order it was given. It runs {@code
 * BASH} by recording each request and answering exit code 0.
 */
final class RecordingProvider implements ExecutionProvider {

  final AtomicInteger starts = new AtomicInteger();
  final AtomicInteger ends = new AtomicInteger();
  final List<ExecutionRequest> executed = new CopyOnWriteArrayList<>();
  volatile SessionContext context;
  Supplier<SessionStartOutcome> start = SessionStartOutcome::accept;
  Runnable end;

  RecordingProvider(List<String> order) {
    this.end = () -> order.add("onSessionEnd");
  }

  @Override
  public Set<Runtime> supportedRuntimes() {
    return Set.of(Runtime.BASH);
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
  public ExecutionResult execute(
      SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
    executed.add(request);
    return new ExecutionResult(0, "ran", "", Duration.ofMillis(1), false);
  }
}
