/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import ai.singlr.repl.sandbox.policy.SandboxPolicy;
import java.nio.file.Path;
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
 * @param sandboxPolicy declarative L2 policy enforced inside the subprocess by {@link
 *     ai.singlr.repl.sandbox.policy.GuardedExecutionControl GuardedExecutionControl}. Defaults to
 *     {@link SandboxPolicy#permissive()}, which is equivalent to no policy layer (the bootstrap's
 *     own default) — non-permissive policies travel to the subprocess via a {@code
 *     --sandbox-policy=<encoded>} argv flag.
 * @param subprocessModules L3 JDK-module restriction applied to the subprocess JVM via {@code
 *     --limit-modules}. Defaults to {@link SubprocessModules#unrestricted()} — current pre-L3
 *     behaviour, all JDK modules observable. Use {@link SubprocessModules#minimal()} to strip
 *     everything beyond the required JShell / bootstrap chain, or {@link
 *     SubprocessModules#allowingExtras(String...)} to add specific JDK modules (e.g. {@code
 *     java.net.http}) on top of the minimal baseline.
 * @param workingDirectory the working directory the subprocess JVM is launched in. {@code null}
 *     (default) means the {@link ai.singlr.repl.sandbox.JvmSandbox JvmSandbox} creates a private
 *     {@code helios-sandbox-cwd-*} temp directory per session, sets it on {@link
 *     ProcessBuilder#directory(java.io.File)}, and deletes it (recursively) when {@link
 *     ai.singlr.repl.sandbox.JvmSandbox#close()} fires — predictable per-session scratch space, no
 *     cross-session leak, no inheritance from the host JVM's cwd. Pass a non-null path to point the
 *     subprocess at a caller-owned directory (e.g. a customer's per-tenant scratch mount);
 *     caller-owned directories are <b>not</b> deleted by {@code close()}, only the ephemeral
 *     default is.
 *     <p>Restricting the working directory is necessary but not sufficient for filesystem
 *     containment — absolute paths bypass cwd entirely. Combine with {@link
 *     SandboxPolicy#denyFileSystemAccess()} to block snippet-direct filesystem reach; the cwd
 *     option contains the relative-path resolution and provides the clean scratch surface, the
 *     policy flag closes the absolute-path and metadata-leak escape routes.
 */
public record JvmSandboxConfig(
    Duration executionTimeout,
    int maxHeapMb,
    Duration callTimeout,
    Duration subprocessStartupTimeout,
    SandboxPolicy sandboxPolicy,
    SubprocessModules subprocessModules,
    Path workingDirectory) {

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

  /** Create a default configuration. */
  public static JvmSandboxConfig defaults() {
    return new JvmSandboxConfig(
        DEFAULT_EXECUTION_TIMEOUT,
        DEFAULT_MAX_HEAP_MB,
        DEFAULT_CALL_TIMEOUT,
        DEFAULT_SUBPROCESS_STARTUP_TIMEOUT,
        SandboxPolicy.permissive(),
        SubprocessModules.unrestricted(),
        null);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private Duration executionTimeout = DEFAULT_EXECUTION_TIMEOUT;
    private int maxHeapMb = DEFAULT_MAX_HEAP_MB;
    private Duration callTimeout = DEFAULT_CALL_TIMEOUT;
    private Duration subprocessStartupTimeout = DEFAULT_SUBPROCESS_STARTUP_TIMEOUT;
    private SandboxPolicy sandboxPolicy = SandboxPolicy.permissive();
    private SubprocessModules subprocessModules = SubprocessModules.unrestricted();
    private Path workingDirectory;

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

    /**
     * Set the {@link SandboxPolicy} the subprocess enforces. Defaults to {@link
     * SandboxPolicy#permissive()} — every snippet is accepted, equivalent to running without a
     * policy layer. Pass a non-permissive policy to opt into bytecode-level deny enforcement (PR 1
     * scaffolding: the policy travels to the subprocess but the verifier currently accepts every
     * byte sequence).
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

    public JvmSandboxConfig build() {
      return new JvmSandboxConfig(
          executionTimeout,
          maxHeapMb,
          callTimeout,
          subprocessStartupTimeout,
          sandboxPolicy,
          subprocessModules,
          workingDirectory);
    }
  }
}
