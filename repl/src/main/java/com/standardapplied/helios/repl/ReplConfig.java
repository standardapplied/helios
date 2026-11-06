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
 * @param sandboxFactory creates the sandbox for each session; must not be {@code null}
 * @param executionTimeout the budget {@link ReplSession#execute(String)} gives a snippet; strictly
 *     positive. Default {@link #DEFAULT_EXECUTION_TIMEOUT}
 * @param maxConcurrentSessions the session cap a caller sizes the {@link
 *     java.util.concurrent.Semaphore} it passes to {@link ReplSession#create} with; strictly
 *     positive. {@code ReplSession} enforces only the semaphore it is given, and {@link
 *     com.standardapplied.helios.repl.execution.JShellExecutionProvider} takes its own cap. Default
 *     {@link #DEFAULT_MAX_CONCURRENT_SESSIONS}
 * @param hostFunctions additional host functions registered, in order, in each session's registry;
 *     must not be {@code null} or hold {@code null}, and names must be distinct (a repeat fails
 *     {@link ReplSession#create})
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

  /**
   * Canonical constructor with full validation; defensively copies {@code hostFunctions}.
   *
   * @throws IllegalArgumentException if {@code sandboxFactory} is {@code null}, {@code
   *     executionTimeout} is {@code null}, zero or negative, or {@code maxConcurrentSessions} is
   *     not positive
   * @throws NullPointerException if {@code hostFunctions} is or holds {@code null}
   */
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

  /**
   * Starts building a {@code ReplConfig} with no sandbox factory, the default timeout and session
   * cap, and no host functions.
   *
   * @return a new builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Fluent, mutable, single-thread builder for {@link ReplConfig}. */
  public static class Builder {
    private SandboxFactory sandboxFactory;
    private Duration executionTimeout = DEFAULT_EXECUTION_TIMEOUT;
    private int maxConcurrentSessions = DEFAULT_MAX_CONCURRENT_SESSIONS;
    private final List<HostFunction> hostFunctions = new ArrayList<>();

    private Builder() {}

    /**
     * Sets the factory that creates each session's sandbox. Required; unset by default.
     *
     * @param sandboxFactory the factory; {@code null} is rejected by {@link #build()}
     * @return this builder
     */
    public Builder withSandboxFactory(SandboxFactory sandboxFactory) {
      this.sandboxFactory = sandboxFactory;
      return this;
    }

    /**
     * Sets the budget {@link ReplSession#execute(String)} gives a snippet; {@link
     * #DEFAULT_EXECUTION_TIMEOUT} by default.
     *
     * @param executionTimeout a strictly positive duration; anything else is rejected by {@link
     *     #build()}
     * @return this builder
     */
    public Builder withExecutionTimeout(Duration executionTimeout) {
      this.executionTimeout = executionTimeout;
      return this;
    }

    /**
     * Sets the cap on concurrent sessions; {@link #DEFAULT_MAX_CONCURRENT_SESSIONS} by default. See
     * {@link ReplConfig#maxConcurrentSessions()} for who enforces it.
     *
     * @param maxConcurrentSessions a positive cap; zero or negative is rejected by {@link #build()}
     * @return this builder
     */
    public Builder withMaxConcurrentSessions(int maxConcurrentSessions) {
      this.maxConcurrentSessions = maxConcurrentSessions;
      return this;
    }

    /**
     * Appends one host function to those every session registers. Order is preserved across calls.
     *
     * @param function the function; {@code null} is rejected by {@link #build()}
     * @return this builder
     */
    public Builder withHostFunction(HostFunction function) {
      this.hostFunctions.add(function);
      return this;
    }

    /**
     * Appends several host functions, in list order, to those every session registers.
     *
     * @param functions the functions; must not be {@code null}, and a {@code null} element is
     *     rejected by {@link #build()}
     * @return this builder
     * @throws NullPointerException if {@code functions} is {@code null}
     */
    public Builder withHostFunctions(List<HostFunction> functions) {
      this.hostFunctions.addAll(functions);
      return this;
    }

    /**
     * Builds the immutable config.
     *
     * @return a new config
     * @throws IllegalArgumentException if the sandbox factory is unset, the timeout is not strictly
     *     positive, or the session cap is not positive
     * @throws NullPointerException if a registered host function is {@code null}
     */
    public ReplConfig build() {
      return new ReplConfig(sandboxFactory, executionTimeout, maxConcurrentSessions, hostFunctions);
    }
  }
}
