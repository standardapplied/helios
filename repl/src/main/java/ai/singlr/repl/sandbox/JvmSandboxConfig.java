/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import java.time.Duration;

/**
 * Configuration for the JVM subprocess sandbox.
 *
 * @param executionTimeout default timeout for code execution
 * @param maxHeapMb maximum heap size for the subprocess in MB
 * @param callTimeout timeout for JSON-RPC calls (host function responses)
 * @param subprocessStartupTimeout maximum wait for the subprocess to connect back on the RPC socket
 *     after launch. Default {@value #DEFAULT_SUBPROCESS_STARTUP_TIMEOUT_SECONDS} s is comfortable
 *     for normal JDK cold-start; raise it when launching off slow storage (NAS, EBS-backed
 *     scratch), when security software scans the binary on first exec, or when launching under
 *     instrumentation (profilers, native-image init). Exceeded launches fail with an {@code
 *     IOException} whose message names the configured duration so the failure mode is debuggable.
 */
public record JvmSandboxConfig(
    Duration executionTimeout,
    int maxHeapMb,
    Duration callTimeout,
    Duration subprocessStartupTimeout) {

  /** Default execution timeout: 30 seconds. */
  public static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofSeconds(30);

  /** Default max heap: 256 MB. */
  public static final int DEFAULT_MAX_HEAP_MB = 256;

  /** Default JSON-RPC call timeout: 60 seconds. */
  public static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(60);

  private static final int DEFAULT_SUBPROCESS_STARTUP_TIMEOUT_SECONDS = 10;

  /** Default subprocess startup timeout: 10 seconds. */
  public static final Duration DEFAULT_SUBPROCESS_STARTUP_TIMEOUT =
      Duration.ofSeconds(DEFAULT_SUBPROCESS_STARTUP_TIMEOUT_SECONDS);

  public JvmSandboxConfig {
    if (executionTimeout == null || executionTimeout.isNegative() || executionTimeout.isZero()) {
      throw new IllegalArgumentException("Execution timeout must be positive");
    }
    if (maxHeapMb <= 0) {
      throw new IllegalArgumentException("Max heap must be positive");
    }
    if (callTimeout == null || callTimeout.isNegative() || callTimeout.isZero()) {
      throw new IllegalArgumentException("Call timeout must be positive");
    }
    if (subprocessStartupTimeout == null
        || subprocessStartupTimeout.isNegative()
        || subprocessStartupTimeout.isZero()) {
      throw new IllegalArgumentException("Subprocess startup timeout must be positive");
    }
  }

  /** Create a default configuration. */
  public static JvmSandboxConfig defaults() {
    return new JvmSandboxConfig(
        DEFAULT_EXECUTION_TIMEOUT,
        DEFAULT_MAX_HEAP_MB,
        DEFAULT_CALL_TIMEOUT,
        DEFAULT_SUBPROCESS_STARTUP_TIMEOUT);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private Duration executionTimeout = DEFAULT_EXECUTION_TIMEOUT;
    private int maxHeapMb = DEFAULT_MAX_HEAP_MB;
    private Duration callTimeout = DEFAULT_CALL_TIMEOUT;
    private Duration subprocessStartupTimeout = DEFAULT_SUBPROCESS_STARTUP_TIMEOUT;

    private Builder() {}

    public Builder withExecutionTimeout(Duration executionTimeout) {
      this.executionTimeout = executionTimeout;
      return this;
    }

    public Builder withMaxHeapMb(int maxHeapMb) {
      this.maxHeapMb = maxHeapMb;
      return this;
    }

    public Builder withCallTimeout(Duration callTimeout) {
      this.callTimeout = callTimeout;
      return this;
    }

    /**
     * Override the default {@value #DEFAULT_SUBPROCESS_STARTUP_TIMEOUT_SECONDS}-second wait for the
     * subprocess to connect back on its RPC socket. See the {@link JvmSandboxConfig record javadoc}
     * for when to raise it.
     */
    public Builder withSubprocessStartupTimeout(Duration subprocessStartupTimeout) {
      this.subprocessStartupTimeout = subprocessStartupTimeout;
      return this;
    }

    public JvmSandboxConfig build() {
      return new JvmSandboxConfig(
          executionTimeout, maxHeapMb, callTimeout, subprocessStartupTimeout);
    }
  }
}
