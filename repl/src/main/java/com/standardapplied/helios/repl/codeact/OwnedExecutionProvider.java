/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.repl.codeact;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.repl.execution.JShellExecutionProvider;
import com.standardapplied.helios.session.execution.ExecutionCapabilities;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.execution.ExecutionRequest;
import com.standardapplied.helios.session.execution.ExecutionResult;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * {@link ExecutionProvider} decorator that ties the lifetime of a wrapped {@link
 * JShellExecutionProvider} to a single Helios session.
 *
 * <p>Used by {@link CodeActPreset} where every preset application constructs its own provider
 * dedicated to one {@link com.standardapplied.helios.session.AgentSession}. The decorator forwards
 * every call to the wrapped provider unchanged, then — when the session ends — closes the wrapped
 * provider so its resources release deterministically with the session. This eliminates the design
 * hazard the Phase 5/6 review surfaced: building many {@code SessionOptions} (one per request, say)
 * used to accumulate JVM shutdown hooks because the bare {@link JShellExecutionProvider} installed
 * one each. Combined with constructing the provider via {@link
 * JShellExecutionProvider.Builder#withShutdownHook withShutdownHook(false)}, ownership is now
 * unambiguous: the session owns the provider for its lifetime.
 *
 * <p>{@code AutoCloseable} so callers that build a preset but never run a session can still drain
 * resources via try-with-resources.
 *
 * <p>Package-private — the preset is the intended caller.
 */
final class OwnedExecutionProvider implements ExecutionProvider, AutoCloseable {

  private final JShellExecutionProvider delegate;

  OwnedExecutionProvider(JShellExecutionProvider delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
  }

  JShellExecutionProvider delegate() {
    return delegate;
  }

  @Override
  public ExecutionCapabilities capabilities() {
    return delegate.capabilities();
  }

  @Override
  public SessionStartOutcome onSessionStart(SessionContext ctx) {
    return delegate.onSessionStart(ctx);
  }

  @Override
  public void onSessionEnd(SessionContext ctx) {
    try {
      delegate.onSessionEnd(ctx);
    } finally {
      delegate.close();
    }
  }

  @Override
  public CompletionStage<ExecutionResult> execute(
      SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
    return delegate.execute(session, request, cancellation);
  }

  @Override
  public void close() {
    delegate.close();
  }
}
