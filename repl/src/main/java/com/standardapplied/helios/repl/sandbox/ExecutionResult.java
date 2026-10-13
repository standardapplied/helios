/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox;

import java.time.Duration;

/**
 * Result of executing code in a sandbox.
 *
 * @param stdout captured standard output
 * @param stderr captured standard error
 * @param exitCode the exit code (0 = success)
 * @param timedOut whether the snippet outlived its timeout, or the sandbox did not answer the
 *     execute within the snippet's timeout, its stop grace and the call timeout, and was closed
 * @param duration wall-clock time the sandbox spent on this execute, measured around {@link
 *     Sandbox#execute}. {@link Duration#ZERO} when not measured
 */
public record ExecutionResult(
    String stdout, String stderr, int exitCode, boolean timedOut, Duration duration) {

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
   */
  public ExecutionResult withDuration(Duration duration) {
    return new ExecutionResult(stdout, stderr, exitCode, timedOut, duration);
  }

  /** Whether the execution completed successfully (exit code 0). */
  public boolean succeeded() {
    return exitCode == 0;
  }

  /** Create a successful result with stdout only. */
  public static ExecutionResult success(String stdout) {
    return newBuilder().withStdout(stdout).build();
  }

  /** Create a failure result with stderr. */
  public static ExecutionResult failure(String stderr) {
    return failure(stderr, 1);
  }

  /** Create a failure result with a specific exit code. */
  public static ExecutionResult failure(String stderr, int exitCode) {
    return newBuilder().withStderr(stderr).withExitCode(exitCode).build();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private String stdout = "";
    private String stderr = "";
    private int exitCode;
    private boolean timedOut;
    private Duration duration = Duration.ZERO;

    private Builder() {}

    public Builder withStdout(String stdout) {
      this.stdout = stdout;
      return this;
    }

    public Builder withStderr(String stderr) {
      this.stderr = stderr;
      return this;
    }

    public Builder withExitCode(int exitCode) {
      this.exitCode = exitCode;
      return this;
    }

    public Builder withTimedOut(boolean timedOut) {
      this.timedOut = timedOut;
      return this;
    }

    public Builder withDuration(Duration duration) {
      this.duration = duration == null ? Duration.ZERO : duration;
      return this;
    }

    public ExecutionResult build() {
      return new ExecutionResult(stdout, stderr, exitCode, timedOut, duration);
    }
  }
}
