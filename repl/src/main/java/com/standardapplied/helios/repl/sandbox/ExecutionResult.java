/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox;

import java.time.Duration;

/**
 * Result of executing code in a sandbox.
 *
 * @param stdout captured standard output; {@code null} is stored as the empty string
 * @param stderr captured standard error; {@code null} is stored as the empty string
 * @param exitCode the exit code: 0 is success, any other value a failure (the sandbox reports 1 for
 *     a failed or timed-out snippet)
 * @param timedOut whether the snippet outlived its timeout, or the sandbox did not answer the
 *     execute within the snippet's timeout, its stop grace and the call timeout, and was closed
 * @param duration wall-clock time the sandbox spent on this execute, measured around {@link
 *     Sandbox#execute}. {@link Duration#ZERO} when not measured; {@code null} or a negative value
 *     is stored as {@link Duration#ZERO}
 */
public record ExecutionResult(
    String stdout, String stderr, int exitCode, boolean timedOut, Duration duration) {

  /** Normalises {@code null} output to the empty string and a missing duration to zero. */
  public ExecutionResult {
    if (stdout == null) {
      stdout = "";
    }
    if (stderr == null) {
      stderr = "";
    }
    if (duration == null || duration.isNegative()) {
      duration = Duration.ZERO;
    }
  }

  /**
   * Return a copy of this result with the given duration. Used by {@link
   * com.standardapplied.helios.repl.ReplSession#execute(String)} to stamp wall-clock timing onto
   * the value the sandbox returned.
   *
   * @param duration the measured wall-clock time; {@code null} or negative is stored as {@link
   *     Duration#ZERO}
   * @return a new result equal to this one except for its duration
   */
  public ExecutionResult withDuration(Duration duration) {
    return new ExecutionResult(stdout, stderr, exitCode, timedOut, duration);
  }

  /**
   * Returns whether the execution completed successfully.
   *
   * @return {@code true} exactly when {@link #exitCode()} is 0
   */
  public boolean succeeded() {
    return exitCode == 0;
  }

  /**
   * Creates a successful result with stdout only: exit code 0, empty stderr, not timed out, zero
   * duration.
   *
   * @param stdout the captured output; {@code null} is stored as the empty string
   * @return the successful result
   */
  public static ExecutionResult success(String stdout) {
    return newBuilder().withStdout(stdout).build();
  }

  /**
   * Creates a failure result with exit code 1, empty stdout, not timed out, zero duration.
   *
   * @param stderr the error output; {@code null} is stored as the empty string
   * @return the failure result
   */
  public static ExecutionResult failure(String stderr) {
    return failure(stderr, 1);
  }

  /**
   * Creates a failure result with a specific exit code, empty stdout, not timed out, zero duration.
   *
   * @param stderr the error output; {@code null} is stored as the empty string
   * @param exitCode the exit code to report; 0 makes {@link #succeeded()} return {@code true}
   * @return the result
   */
  public static ExecutionResult failure(String stderr, int exitCode) {
    return newBuilder().withStderr(stderr).withExitCode(exitCode).build();
  }

  /**
   * Starts a builder for a result; every setting defaults to that of {@link #success(String)
   * success("")}.
   *
   * @return a new builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Mutable, single-thread builder for {@link ExecutionResult}; each setter replaces its value. */
  public static class Builder {
    private String stdout = "";
    private String stderr = "";
    private int exitCode;
    private boolean timedOut;
    private Duration duration = Duration.ZERO;

    private Builder() {}

    /**
     * Sets the captured standard output; empty by default.
     *
     * @param stdout the output; {@code null} is stored as the empty string
     * @return this builder
     */
    public Builder withStdout(String stdout) {
      this.stdout = stdout;
      return this;
    }

    /**
     * Sets the captured standard error; empty by default.
     *
     * @param stderr the error output; {@code null} is stored as the empty string
     * @return this builder
     */
    public Builder withStderr(String stderr) {
      this.stderr = stderr;
      return this;
    }

    /**
     * Sets the exit code; 0 (success) by default.
     *
     * @param exitCode 0 for success, any other value for failure
     * @return this builder
     */
    public Builder withExitCode(int exitCode) {
      this.exitCode = exitCode;
      return this;
    }

    /**
     * Sets whether the execution timed out; {@code false} by default.
     *
     * @param timedOut whether the snippet or the sandbox's reply outlived its time budget
     * @return this builder
     */
    public Builder withTimedOut(boolean timedOut) {
      this.timedOut = timedOut;
      return this;
    }

    /**
     * Sets the wall-clock time of the execution; {@link Duration#ZERO} (not measured) by default.
     *
     * @param duration the elapsed time; {@code null} or negative is stored as {@link Duration#ZERO}
     * @return this builder
     */
    public Builder withDuration(Duration duration) {
      this.duration = duration == null ? Duration.ZERO : duration;
      return this;
    }

    /**
     * Builds the result. Never throws: every value is normalised as the record's constructor does.
     *
     * @return a new result
     */
    public ExecutionResult build() {
      return new ExecutionResult(stdout, stderr, exitCode, timedOut, duration);
    }
  }
}
