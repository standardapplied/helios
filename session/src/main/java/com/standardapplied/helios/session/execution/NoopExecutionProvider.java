/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.runtime.SessionContext;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The safe default {@link ExecutionProvider}: refuses every request with a non-zero exit and a
 * clear stderr message. Library mode wires this by default so a session that forgot to configure an
 * execution provider cannot silently shell out to the host.
 *
 * <p>Callers that genuinely want execution must wire a {@code LocalProcessExecutionProvider} (or
 * their own implementation) via {@link
 * ai.singlr.session.SessionOptions.Builder#withExecutionProvider(ExecutionProvider)}.
 *
 * <h2>Thread-safety</h2>
 *
 * Stateless and immutable; the singleton {@link #INSTANCE} is safe to share across every session.
 */
public final class NoopExecutionProvider implements ExecutionProvider {

  /** The shared instance — there is no useful per-session state to capture. */
  public static final NoopExecutionProvider INSTANCE = new NoopExecutionProvider();

  private static final ExecutionCapabilities CAPABILITIES =
      ExecutionCapabilities.newBuilder()
          .withSupportedRuntimes(Set.of())
          .withNetworkAllowed(false)
          .withFilesystemWriteAllowed(false)
          .withMaxTimeout(Duration.ofSeconds(1))
          .build();

  private NoopExecutionProvider() {}

  @Override
  public ExecutionCapabilities capabilities() {
    return CAPABILITIES;
  }

  @Override
  public CompletionStage<ExecutionResult> execute(
      SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
    Objects.requireNonNull(session, "session must not be null");
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    var result =
        new ExecutionResult(
            -1,
            "",
            "execution is disabled for this session — NoopExecutionProvider refuses every runtime"
                + " (requested: "
                + request.runtime()
                + "). Configure LocalProcessExecutionProvider (or your own ExecutionProvider) via"
                + " SessionOptions.Builder#withExecutionProvider to enable execution.",
            Duration.ZERO,
            false,
            Map.of());
    return CompletableFuture.completedFuture(result);
  }
}
