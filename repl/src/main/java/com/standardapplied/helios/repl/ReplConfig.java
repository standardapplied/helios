/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.sandbox.ExecutionResult;
import ai.singlr.repl.sandbox.SandboxFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for REPL sessions. Immutable and reusable across sessions.
 *
 * <p>This is the substrate-only shape: sandbox factory, execution caps, host-function registry, and
 * bindings-snapshot bounds. RLM / CodeAct harness concepts (submit schema, predict budget, required
 * predict signatures, signature matchers) were removed in the v2 cut — predict and submit become
 * session-level Tools in v2, not sandbox host functions, and the iteration / completion surface
 * lives on {@code AgentSession} hooks.
 *
 * @param sandboxFactory creates the sandbox for each session
 * @param executionTimeout default timeout per code execution
 * @param maxConcurrentSessions maximum concurrent sessions (enforced via semaphore)
 * @param hostFunctions additional host functions registered for each session
 * @param maxOutputCharsToModel cap on the size of the {@code execute_code} tool result returned to
 *     the model. The full untruncated output stays in {@link ReplSession#history()} for operators.
 *     Variables persist fully across executions in the sandbox; the printed output the model sees
 *     on each turn is bounded. Defaults to 5000. Set to {@code 0} to disable truncation
 * @param sandboxBindingsListener optional observer of the sandbox's working memory after each
 *     {@code execute_code}. Fires synchronously inside {@link ReplSession#execute(String)} after
 *     the underlying sandbox returns. {@code null} disables the callback (default)
 * @param maxBindingValueChars per-value cap on the sandbox-side {@code toString} repr emitted in
 *     {@link ExecutionResult#bindings()}. Defaults to {@value #DEFAULT_MAX_BINDING_VALUE_CHARS}.
 *     Set to {@code 0} to disable per-value truncation
 * @param maxBindingSnapshotChars total cap across all values in a single bindings snapshot.
 *     Defaults to {@value #DEFAULT_MAX_BINDING_SNAPSHOT_CHARS}. Once exceeded, remaining variables
 *     are dropped from the snapshot. Set to {@code 0} to disable the total cap
 * @param maxExecutedCodeChars per-call cap on the {@code executedCode} field returned in {@link
 *     ExecutionResult}. Defaults to {@value #DEFAULT_MAX_EXECUTED_CODE_CHARS}. Set to {@code 0} to
 *     disable per-call truncation (full snippet always returned). Truncation appends a {@code ...
 *     (len=N)} marker so consumers know the original length
 */
public record ReplConfig(
    SandboxFactory sandboxFactory,
    Duration executionTimeout,
    int maxConcurrentSessions,
    List<HostFunction> hostFunctions,
    int maxOutputCharsToModel,
    SandboxBindingsListener sandboxBindingsListener,
    int maxBindingValueChars,
    int maxBindingSnapshotChars,
    int maxExecutedCodeChars) {

  /** Default execution timeout: 30 seconds. */
  public static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofSeconds(30);

  /** Default max concurrent sessions. */
  public static final int DEFAULT_MAX_CONCURRENT_SESSIONS = 50;

  /** Default {@code execute_code} output cap shown to the model: 5000 chars. */
  public static final int DEFAULT_MAX_OUTPUT_CHARS_TO_MODEL = 5000;

  /** Default per-value cap on the {@code toString} repr in a bindings snapshot. */
  public static final int DEFAULT_MAX_BINDING_VALUE_CHARS = 200;

  /** Default total cap on a bindings snapshot. */
  public static final int DEFAULT_MAX_BINDING_SNAPSHOT_CHARS = 16 * 1024;

  /**
   * Default per-call cap on the {@code executedCode} field. Matches the model-facing output cap so
   * a 5K snippet renders the same as 5K of stdout — keeps live-UI displays aligned.
   */
  public static final int DEFAULT_MAX_EXECUTED_CODE_CHARS = 5000;

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
    if (maxOutputCharsToModel < 0) {
      throw new IllegalArgumentException(
          "maxOutputCharsToModel must be >= 0 (0 disables truncation)");
    }
    if (maxBindingValueChars < 0) {
      throw new IllegalArgumentException(
          "maxBindingValueChars must be >= 0 (0 disables per-value truncation)");
    }
    if (maxBindingSnapshotChars < 0) {
      throw new IllegalArgumentException(
          "maxBindingSnapshotChars must be >= 0 (0 disables the total cap)");
    }
    if (maxExecutedCodeChars < 0) {
      throw new IllegalArgumentException(
          "maxExecutedCodeChars must be >= 0 (0 disables per-call truncation)");
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
    private int maxOutputCharsToModel = DEFAULT_MAX_OUTPUT_CHARS_TO_MODEL;
    private SandboxBindingsListener sandboxBindingsListener;
    private int maxBindingValueChars = DEFAULT_MAX_BINDING_VALUE_CHARS;
    private int maxBindingSnapshotChars = DEFAULT_MAX_BINDING_SNAPSHOT_CHARS;
    private int maxExecutedCodeChars = DEFAULT_MAX_EXECUTED_CODE_CHARS;

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

    /** Cap on the printable {@code execute_code} output shown to the model. */
    public Builder withMaxOutputCharsToModel(int maxOutputCharsToModel) {
      this.maxOutputCharsToModel = maxOutputCharsToModel;
      return this;
    }

    /**
     * Listener that observes the sandbox's working memory after each {@code execute_code}. {@code
     * null} disables the callback (default).
     */
    public Builder withSandboxBindingsListener(SandboxBindingsListener listener) {
      this.sandboxBindingsListener = listener;
      return this;
    }

    /** Per-value cap on the bindings snapshot. */
    public Builder withMaxBindingValueChars(int chars) {
      this.maxBindingValueChars = chars;
      return this;
    }

    /** Total cap on a single bindings snapshot. */
    public Builder withMaxBindingSnapshotChars(int chars) {
      this.maxBindingSnapshotChars = chars;
      return this;
    }

    /**
     * Per-call cap on {@link ExecutionResult#executedCode()}. {@code 0} disables truncation (full
     * snippet returned). Truncated values get a {@code ... (len=N)} marker.
     */
    public Builder withMaxExecutedCodeChars(int chars) {
      this.maxExecutedCodeChars = chars;
      return this;
    }

    /** Build the immutable config. */
    public ReplConfig build() {
      return new ReplConfig(
          sandboxFactory,
          executionTimeout,
          maxConcurrentSessions,
          hostFunctions,
          maxOutputCharsToModel,
          sandboxBindingsListener,
          maxBindingValueChars,
          maxBindingSnapshotChars,
          maxExecutedCodeChars);
    }
  }
}
