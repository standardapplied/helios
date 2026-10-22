/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.execution;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * The safe default {@link ExecutionProvider}: refuses every request with a non-zero exit and a
 * clear stderr message. Library mode wires this by default so a session that forgot to configure an
 * execution provider cannot silently shell out to the host.
 *
 * <p>Callers that genuinely want execution must wire a {@code LocalProcessExecutionProvider} (or
 * their own implementation) via {@link
 * com.standardapplied.helios.session.SessionOptions.Builder#withExecutionProvider(ExecutionProvider)}.
 *
 * <h2>Thread-safety</h2>
 *
 * Stateless and immutable; the singleton {@link #INSTANCE} is safe to share across every session.
 */
public final class NoopExecutionProvider implements ExecutionProvider {

  /** The shared instance — there is no useful per-session state to capture. */
  public static final NoopExecutionProvider INSTANCE = new NoopExecutionProvider();

  private NoopExecutionProvider() {}

  @Override
  public Set<Runtime> supportedRuntimes() {
    return Set.of();
  }

  @Override
  public ExecutionResult execute(
      SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
    Objects.requireNonNull(session, "session must not be null");
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    return new ExecutionResult(
        -1,
        "",
        "execution is disabled for this session — NoopExecutionProvider refuses every runtime"
            + " (requested: "
            + request.runtime()
            + "). Configure LocalProcessExecutionProvider (or your own ExecutionProvider) via"
            + " SessionOptions.Builder#withExecutionProvider to enable execution.",
        Duration.ZERO,
        false);
  }
}
