/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.execution;

import com.standardapplied.helios.core.common.Validate;
import java.time.Duration;
import java.util.Objects;

/**
 * Outcome of a single {@link
 * ExecutionProvider#execute(com.standardapplied.helios.core.tool.SessionContext, ExecutionRequest,
 * com.standardapplied.helios.core.model.CancellationToken) execute} call. The text is raw: the
 * session redacts the {@code Execute} tool's output against its secret registry before anything
 * sees it.
 *
 * <p>Naming overlaps with {@code com.standardapplied.helios.repl.sandbox.ExecutionResult} — that
 * one is internal to the JShell sandbox subprocess protocol and stays scoped to its package. This
 * is the v2 session- level result the {@code Execute} tool wraps into a {@link
 * com.standardapplied.helios.core.tool.ToolResult}.
 *
 * @param exitCode the process exit code, or {@code -1} on timeout / refusal / unsupported runtime.
 *     Providers should reserve {@code -1} for "did not produce a normal exit" so the model can
 *     distinguish process failure from runtime refusal via {@link #timedOut()} and the {@code
 *     stderr} message
 * @param stdout standard output text (UTF-8 decoded). Empty string when the child produced no
 *     output; never {@code null}
 * @param stderr standard error text (UTF-8 decoded). Empty string when the child produced no
 *     output; never {@code null}
 * @param duration wall-clock time the invocation spent — including process startup and reaping for
 *     local providers, including network round-trip for remote providers. Non-null
 * @param timedOut {@code true} if the provider killed the invocation because it exceeded the
 *     configured timeout. When set, {@code exitCode} is {@code -1} by convention
 */
public record ExecutionResult(
    int exitCode, String stdout, String stderr, Duration duration, boolean timedOut) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code stdout}, {@code stderr} or {@code duration} is null
   * @throws IllegalArgumentException if {@code duration} is negative
   */
  public ExecutionResult {
    Objects.requireNonNull(stdout, "stdout must not be null");
    Objects.requireNonNull(stderr, "stderr must not be null");
    Validate.nonNegativeDuration("duration", duration);
  }

  /**
   * Build a refusal-shaped result: exit code {@code -1}, empty stdout, the supplied {@code stderr}
   * carrying the reason, zero duration, {@code timedOut=false}. The common shape providers return
   * when they cannot accept a request (unsupported runtime, missing sandbox, provider closed).
   * Centralising the shape here keeps every refusal call site honest about what an {@code
   * exitCode==-1} result looks like.
   *
   * @param stderr human-readable reason; non-null and non-blank
   * @return the refusal result
   * @throws NullPointerException if {@code stderr} is null
   * @throws IllegalArgumentException if {@code stderr} is blank
   */
  public static ExecutionResult refusal(String stderr) {
    Validate.notBlank("stderr", stderr);
    return new ExecutionResult(-1, "", stderr, Duration.ZERO, false);
  }

  /**
   * Start building an {@code ExecutionResult}.
   *
   * @return a fresh builder with empty stdout/stderr, exit code 0, zero duration and {@code
   *     timedOut=false}
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Mutable builder for {@link ExecutionResult}. */
  public static final class Builder {

    private int exitCode;
    private String stdout = "";
    private String stderr = "";
    private Duration duration = Duration.ZERO;
    private boolean timedOut;

    private Builder() {}

    /**
     * Set the exit code. Defaults to {@code 0}.
     *
     * @param exitCode the process exit code
     * @return this builder
     */
    public Builder withExitCode(int exitCode) {
      this.exitCode = exitCode;
      return this;
    }

    /**
     * Set the stdout. Defaults to {@code ""}.
     *
     * @param stdout non-null string
     * @return this builder
     * @throws NullPointerException if {@code stdout} is null
     */
    public Builder withStdout(String stdout) {
      this.stdout = Objects.requireNonNull(stdout, "stdout must not be null");
      return this;
    }

    /**
     * Set the stderr. Defaults to {@code ""}.
     *
     * @param stderr non-null string
     * @return this builder
     * @throws NullPointerException if {@code stderr} is null
     */
    public Builder withStderr(String stderr) {
      this.stderr = Objects.requireNonNull(stderr, "stderr must not be null");
      return this;
    }

    /**
     * Set the wall-clock duration. Defaults to {@link Duration#ZERO}.
     *
     * @param duration non-null, non-negative duration
     * @return this builder
     * @throws NullPointerException if {@code duration} is null
     * @throws IllegalArgumentException if {@code duration} is negative
     */
    public Builder withDuration(Duration duration) {
      this.duration = Validate.nonNegativeDuration("duration", duration);
      return this;
    }

    /**
     * Set the timeout flag. Defaults to {@code false}.
     *
     * @param timedOut true if the invocation was killed for exceeding its timeout
     * @return this builder
     */
    public Builder withTimedOut(boolean timedOut) {
      this.timedOut = timedOut;
      return this;
    }

    /**
     * Build the immutable record.
     *
     * @return the result
     */
    public ExecutionResult build() {
      return new ExecutionResult(exitCode, stdout, stderr, duration, timedOut);
    }
  }
}
