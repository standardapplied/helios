/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl;

import com.standardapplied.helios.repl.host.HostFunction;
import com.standardapplied.helios.repl.sandbox.SandboxFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for REPL sessions. Immutable and reusable across sessions.
 *
 * <p>This is the substrate-only shape: sandbox factory, execution caps and host-function registry.
 *
 * @param sandboxFactory creates the sandbox for each session
 * @param executionTimeout default timeout per code execution
 * @param maxConcurrentSessions maximum concurrent sessions (enforced via semaphore)
 * @param hostFunctions additional host functions registered for each session
 */
public record ReplConfig(
    SandboxFactory sandboxFactory,
    Duration executionTimeout,
    int maxConcurrentSessions,
    List<HostFunction> hostFunctions) {

  /** Default execution timeout: 30 seconds. */
  public static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofSeconds(30);

  /** Default max concurrent sessions. */
  public static final int DEFAULT_MAX_CONCURRENT_SESSIONS = 50;

  /** Canonical constructor with full validation; defensively copies {@code hostFunctions}. */
  public ReplConfig {
    if (sandboxFactory == null) {
      throw new IllegalArgumentException("Sandbox factory must not be null");
    }
    if (executionTimeout == null || executionTimeout.isNegative() || executionTimeout.isZero()) {
      throw new IllegalArgumentException("Execution timeout must be positive");
    }
    if (maxConcurrentSessions <= 0) {
      throw new IllegalArgumentException("Max concurrent sessions must be positive");
    }
    hostFunctions = List.copyOf(hostFunctions);
  }

  /** Start building a {@code ReplConfig}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Fluent builder for {@link ReplConfig}. */
  public static class Builder {
    private SandboxFactory sandboxFactory;
    private Duration executionTimeout = DEFAULT_EXECUTION_TIMEOUT;
    private int maxConcurrentSessions = DEFAULT_MAX_CONCURRENT_SESSIONS;
    private final List<HostFunction> hostFunctions = new ArrayList<>();

    private Builder() {}

    /** Set the sandbox factory. Required. */
    public Builder withSandboxFactory(SandboxFactory sandboxFactory) {
      this.sandboxFactory = sandboxFactory;
      return this;
    }

    /** Set the per-execute timeout. Must be strictly positive. */
    public Builder withExecutionTimeout(Duration executionTimeout) {
      this.executionTimeout = executionTimeout;
      return this;
    }

    /** Set the cap on concurrent sessions. Must be strictly positive. */
    public Builder withMaxConcurrentSessions(int maxConcurrentSessions) {
      this.maxConcurrentSessions = maxConcurrentSessions;
      return this;
    }

    /** Register one additional host function. Order is preserved across calls. */
    public Builder withHostFunction(HostFunction function) {
      this.hostFunctions.add(function);
      return this;
    }

    /** Register several host functions in one call. */
    public Builder withHostFunctions(List<HostFunction> functions) {
      this.hostFunctions.addAll(functions);
      return this;
    }

    /** Build the immutable config. */
    public ReplConfig build() {
      return new ReplConfig(sandboxFactory, executionTimeout, maxConcurrentSessions, hostFunctions);
    }
  }
}
