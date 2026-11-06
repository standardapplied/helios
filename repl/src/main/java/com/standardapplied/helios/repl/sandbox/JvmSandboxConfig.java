/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox;

import com.standardapplied.helios.repl.sandbox.policy.SandboxPolicy;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuration for the JVM subprocess sandbox.
 *
 * @param executionTimeout the time a snippet may run when its {@link ExecutionRequest} names no
 *     timeout; strictly positive. Default {@link #DEFAULT_EXECUTION_TIMEOUT}
 * @param stopGrace how long a snippet that outlived its execution timeout has, once stopped, to end
 *     together with every thread it started. A snippet still running after the grace is blocked
 *     where nothing in the JVM reaches it, so the sandbox answers the execute and exits with code
 *     3. Default {@link #DEFAULT_STOP_GRACE}; travels to the subprocess as a {@code
 *     --stop-grace=<ISO-8601 duration>} argv flag.
 * @param maxHeapMb maximum heap size for the subprocess in MB, passed as {@code -Xmx<n>m}; strictly
 *     positive. Default {@link #DEFAULT_MAX_HEAP_MB}
 * @param callTimeout how long the host waits for the subprocess to answer a call such as installing
 *     the host-function wrappers, and the margin an execute's reply has to arrive once its
 *     snippet's timeout and stop grace are over; strictly positive. Default {@link
 *     #DEFAULT_CALL_TIMEOUT}
 * @param subprocessStartupTimeout maximum wait for the subprocess to connect back on the RPC socket
 *     after launch. Default {@value #DEFAULT_SUBPROCESS_STARTUP_TIMEOUT_SECONDS} s is comfortable
 *     for normal JDK cold-start; raise it when launching off slow storage (NAS, EBS-backed
 *     scratch), when security software scans the binary on first exec, or when launching under
 *     instrumentation (profilers, native-image init). Exceeded launches fail with an {@code
 *     IOException} whose message names the configured duration so the failure mode is debuggable.
 * @param sandboxPolicy declarative L2 policy enforced inside the subprocess by {@link
 *     com.standardapplied.helios.repl.sandbox.policy.GuardedExecutionControl
 *     GuardedExecutionControl}. Defaults to {@link SandboxPolicy#permissive()}, which is equivalent
 *     to no policy layer (the bootstrap's own default) — non-permissive policies travel to the
 *     subprocess via a {@code --sandbox-policy=<encoded>} argv flag.
 * @param subprocessModules L3 JDK-module restriction applied to the subprocess JVM via {@code
 *     --limit-modules}. Defaults to {@link SubprocessModules#unrestricted()} — current pre-L3
 *     behaviour, all JDK modules observable. Use {@link SubprocessModules#minimal()} to strip
 *     everything beyond the required JShell / bootstrap chain, or {@link
 *     SubprocessModules#allowingExtras(String...)} to add specific JDK modules (e.g. {@code
 *     java.net.http}) on top of the minimal baseline.
 * @param workingDirectory the working directory the subprocess JVM is launched in. {@code null}
 *     (default) means the {@link com.standardapplied.helios.repl.sandbox.JvmSandbox JvmSandbox}
 *     creates a private {@code helios-sandbox-cwd-*} temp directory per session, sets it on {@link
 *     ProcessBuilder#directory(java.io.File)}, and deletes it (recursively) when {@link
 *     com.standardapplied.helios.repl.sandbox.JvmSandbox#close()} fires — predictable per-session
 *     scratch space, no cross-session leak, no inheritance from the host JVM's cwd. Pass a non-null
 *     path to point the subprocess at a caller-owned directory (e.g. a customer's per-tenant
 *     scratch mount); caller-owned directories are <b>not</b> deleted by {@code close()}, only the
 *     ephemeral default is.
 *     <p>Restricting the working directory is necessary but not sufficient for filesystem
 *     containment — absolute paths bypass cwd entirely. Combine with {@link
 *     SandboxPolicy#denyFileSystemAccess()} to block snippet-direct filesystem reach; the cwd
 *     option contains the relative-path resolution and provides the clean scratch surface, the
 *     policy flag closes the absolute-path and metadata-leak escape routes.
 */
public record JvmSandboxConfig(
    Duration executionTimeout,
    Duration stopGrace,
    int maxHeapMb,
    Duration callTimeout,
    Duration subprocessStartupTimeout,
    SandboxPolicy sandboxPolicy,
    SubprocessModules subprocessModules,
    Path workingDirectory) {

  /** Default execution timeout: 30 seconds. */
  public static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofSeconds(30);

  /** Default stop grace: 5 seconds. */
  public static final Duration DEFAULT_STOP_GRACE = Duration.ofSeconds(5);

  /** Default max heap: 256 MB. */
  public static final int DEFAULT_MAX_HEAP_MB = 256;

  /** Default JSON-RPC call timeout: 60 seconds. */
  public static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(60);

  private static final int DEFAULT_SUBPROCESS_STARTUP_TIMEOUT_SECONDS = 10;

  private static final Duration LONGEST_REPLY_WAIT = Duration.ofMillis(Long.MAX_VALUE);

  /** Default subprocess startup timeout: 10 seconds. */
  public static final Duration DEFAULT_SUBPROCESS_STARTUP_TIMEOUT =
      Duration.ofSeconds(DEFAULT_SUBPROCESS_STARTUP_TIMEOUT_SECONDS);

  /**
   * Validates the components; {@code workingDirectory} may be {@code null} and is not checked for
   * existence (a bad path fails the launch instead).
   *
   * @throws IllegalArgumentException if any duration is {@code null}, zero or negative, if {@code
   *     maxHeapMb} is not positive, or if {@code sandboxPolicy} or {@code subprocessModules} is
   *     {@code null}
   */
  public JvmSandboxConfig {
    if (executionTimeout == null || executionTimeout.isNegative() || executionTimeout.isZero()) {
      throw new IllegalArgumentException("Execution timeout must be positive");
    }
    if (stopGrace == null || stopGrace.isNegative() || stopGrace.isZero()) {
      throw new IllegalArgumentException("Stop grace must be positive");
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
    if (sandboxPolicy == null) {
      throw new IllegalArgumentException("Sandbox policy must not be null");
    }
    if (subprocessModules == null) {
      throw new IllegalArgumentException("Subprocess modules must not be null");
    }
    // workingDirectory is intentionally nullable — null signals "create ephemeral per-session
    // temp dir". When non-null we don't validate existence here; JvmSandbox will surface a
    // launch-time IOException if ProcessBuilder.directory() rejects the path.
  }

  /**
   * Creates a default configuration.
   *
   * @return a configuration holding every {@code DEFAULT_*} constant, a {@link
   *     SandboxPolicy#permissive() permissive} policy, {@link SubprocessModules#unrestricted()
   *     unrestricted} modules and the ephemeral working directory
   */
  public static JvmSandboxConfig defaults() {
    return new JvmSandboxConfig(
        DEFAULT_EXECUTION_TIMEOUT,
        DEFAULT_STOP_GRACE,
        DEFAULT_MAX_HEAP_MB,
        DEFAULT_CALL_TIMEOUT,
        DEFAULT_SUBPROCESS_STARTUP_TIMEOUT,
        SandboxPolicy.permissive(),
        SubprocessModules.unrestricted(),
        null);
  }

  /**
   * How long the host waits for the reply to an execute whose snippet has {@code budget}: the
   * budget, then the {@link #stopGrace()} the sandbox may spend stopping the snippet, then the
   * {@link #callTimeout()} for the reply to arrive. A sum beyond {@link Long#MAX_VALUE}
   * milliseconds waits that long.
   */
  Duration executeReplyWait(Duration budget) {
    try {
      var wait = budget.plus(stopGrace).plus(callTimeout);
      return wait.compareTo(LONGEST_REPLY_WAIT) < 0 ? wait : LONGEST_REPLY_WAIT;
    } catch (ArithmeticException overflow) {
      return LONGEST_REPLY_WAIT;
    }
  }

  /**
   * Starts a builder whose settings default to those of {@link #defaults()}.
   *
   * @return a new builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Mutable, single-thread builder for {@link JvmSandboxConfig}; each setter replaces its value and
   * {@link #build()} validates them all.
   */
  public static class Builder {
    private Duration executionTimeout = DEFAULT_EXECUTION_TIMEOUT;
    private Duration stopGrace = DEFAULT_STOP_GRACE;
    private int maxHeapMb = DEFAULT_MAX_HEAP_MB;
    private Duration callTimeout = DEFAULT_CALL_TIMEOUT;
    private Duration subprocessStartupTimeout = DEFAULT_SUBPROCESS_STARTUP_TIMEOUT;
    private SandboxPolicy sandboxPolicy = SandboxPolicy.permissive();
    private SubprocessModules subprocessModules = SubprocessModules.unrestricted();
    private Path workingDirectory;

    private Builder() {}

    /**
     * Sets the time a snippet may run when its request names no timeout; {@link
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
     * Override the {@link #DEFAULT_STOP_GRACE} a stopped snippet has to end before the sandbox
     * exits. See the {@link JvmSandboxConfig record javadoc}.
     *
     * @param stopGrace a strictly positive duration; anything else is rejected by {@link #build()}
     * @return this builder
     */
    public Builder withStopGrace(Duration stopGrace) {
      this.stopGrace = stopGrace;
      return this;
    }

    /**
     * Sets the subprocess's maximum heap, in MB; {@link #DEFAULT_MAX_HEAP_MB} by default.
     *
     * @param maxHeapMb a positive size; zero or negative is rejected by {@link #build()}
     * @return this builder
     */
    public Builder withMaxHeapMb(int maxHeapMb) {
      this.maxHeapMb = maxHeapMb;
      return this;
    }

    /**
     * Sets how long the host waits for the subprocess to answer a call, and the margin an execute's
     * reply has after its snippet's timeout and stop grace; {@link #DEFAULT_CALL_TIMEOUT} by
     * default.
     *
     * @param callTimeout a strictly positive duration; anything else is rejected by {@link
     *     #build()}
     * @return this builder
     */
    public Builder withCallTimeout(Duration callTimeout) {
      this.callTimeout = callTimeout;
      return this;
    }

    /**
     * Override the default {@value #DEFAULT_SUBPROCESS_STARTUP_TIMEOUT_SECONDS}-second wait for the
     * subprocess to connect back on its RPC socket. See the {@link JvmSandboxConfig record javadoc}
     * for when to raise it.
     *
     * @param subprocessStartupTimeout a strictly positive duration; anything else is rejected by
     *     {@link #build()}
     * @return this builder
     */
    public Builder withSubprocessStartupTimeout(Duration subprocessStartupTimeout) {
      this.subprocessStartupTimeout = subprocessStartupTimeout;
      return this;
    }

    /**
     * Set the {@link SandboxPolicy} the subprocess enforces. Defaults to {@link
     * SandboxPolicy#permissive()} — every snippet is accepted, equivalent to running without a
     * policy layer. Pass a non-permissive policy to opt into bytecode-level enforcement: the policy
     * travels to the subprocess, whose verifier rejects every class that references a denied API.
     *
     * @param sandboxPolicy the policy; {@code null} is rejected by {@link #build()}
     * @return this builder
     */
    public Builder withSandboxPolicy(SandboxPolicy sandboxPolicy) {
      this.sandboxPolicy = sandboxPolicy;
      return this;
    }

    /**
     * Set the {@link SubprocessModules} restriction. Defaults to {@link
     * SubprocessModules#unrestricted()} — current behaviour, all JDK modules observable in the
     * subprocess. {@link SubprocessModules#minimal()} strips everything except the JShell /
     * bootstrap chain; {@link SubprocessModules#allowingExtras(String...)} adds named JDK modules
     * on top of the minimal baseline.
     *
     * @param subprocessModules the module restriction; {@code null} is rejected by {@link #build()}
     * @return this builder
     */
    public Builder withSubprocessModules(SubprocessModules subprocessModules) {
      this.subprocessModules = subprocessModules;
      return this;
    }

    /**
     * Pin the subprocess JVM's working directory. {@code null} (default) selects the per-session
     * ephemeral scratch dir created by {@link JvmSandbox}; pass a non-null path to point the
     * subprocess at a caller-owned directory. Caller-owned directories are not deleted by {@link
     * JvmSandbox#close()}. See the record javadoc on {@link JvmSandboxConfig#workingDirectory()}
     * for the rationale and the cwd / {@code denyFileSystemAccess} composition guidance.
     *
     * @param workingDirectory caller-owned path or {@code null} for the ephemeral default
     * @return this builder
     */
    public Builder withWorkingDirectory(Path workingDirectory) {
      this.workingDirectory = workingDirectory;
      return this;
    }

    /**
     * Builds the configuration.
     *
     * @return a new configuration
     * @throws IllegalArgumentException if any duration is {@code null}, zero or negative, if the
     *     heap is not positive, or if the policy or module restriction is {@code null}
     */
    public JvmSandboxConfig build() {
      return new JvmSandboxConfig(
          executionTimeout,
          stopGrace,
          maxHeapMb,
          callTimeout,
          subprocessStartupTimeout,
          sandboxPolicy,
          subprocessModules,
          workingDirectory);
    }
  }
}
