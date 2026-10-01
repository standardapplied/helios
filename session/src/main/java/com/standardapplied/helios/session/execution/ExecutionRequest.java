/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import ai.singlr.core.common.Validate;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One request for the {@link ExecutionProvider} to execute. The shape is intentionally provider-
 * agnostic: providers translate it into their own dispatch (a subprocess fork, a JDBC query, a
 * JShell snippet, …).
 *
 * <p>Naming overlaps with {@code ai.singlr.repl.sandbox.ExecutionRequest} — that one is internal to
 * the JShell sandbox subprocess protocol and stays scoped to its package. This is the v2 session-
 * level request that the {@code Execute} tool builds from the model's tool call arguments.
 *
 * @param runtime the dispatch target; non-null
 * @param script the script body to run. For {@link Runtime#BASH} this is the shell snippet executed
 *     under {@code bash -c}; for {@link Runtime#PYTHON} this is the program body passed to {@code
 *     python3 -c}; for {@link Runtime#JSHELL} this is the JShell snippet. Non-blank
 * @param args extra positional arguments visible to the script. Non-null, defensively copied; the
 *     interpretation is per-runtime ({@code BASH} appends them after {@code -c '<script>'} as
 *     {@code $1..$N}; most other runtimes ignore them)
 * @param workingDirectory the working directory for the invocation. {@code null} requests a per-
 *     invocation temp directory (matches {@code CommandGrant}'s default); when set, must be an
 *     existing directory that the provider is willing to enter
 * @param timeout wall-clock budget for the invocation, including process startup. Non-null,
 *     strictly positive; the provider may further clamp against {@link
 *     ExecutionCapabilities#maxTimeout()}
 * @param environment additional environment variables for the child process. Non-null, defensively
 *     copied. Provider-imposed environment (e.g. {@code PATH}) is merged in; this map cannot leak
 *     the JVM-inherited environment because the provider {@link ProcessBuilder#environment()
 *     clear}s before injecting
 * @param stdin optional stdin contents fed to the child. {@code Optional.empty()} closes stdin
 *     immediately after fork; a present value writes the string to the child and closes
 */
public record ExecutionRequest(
    Runtime runtime,
    String script,
    List<String> args,
    Path workingDirectory,
    Duration timeout,
    Map<String, String> environment,
    Optional<String> stdin) {

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code runtime}, {@code script}, {@code args}, {@code timeout},
   *     {@code environment}, or {@code stdin} is null
   * @throws IllegalArgumentException if {@code script} is blank or {@code timeout} is non-positive
   */
  public ExecutionRequest {
    Objects.requireNonNull(runtime, "runtime must not be null");
    Validate.notBlank("script", script);
    Objects.requireNonNull(args, "args must not be null");
    for (var a : args) {
      Objects.requireNonNull(a, "args must not contain null");
    }
    args = List.copyOf(args);
    Validate.positiveDuration("timeout", timeout);
    Objects.requireNonNull(environment, "environment must not be null");
    for (var e : environment.entrySet()) {
      Objects.requireNonNull(e.getKey(), "environment must not contain null keys");
      Objects.requireNonNull(e.getValue(), "environment must not contain null values");
    }
    environment = Map.copyOf(environment);
    Objects.requireNonNull(stdin, "stdin must not be null");
  }

  /**
   * Start building an {@code ExecutionRequest}.
   *
   * @return a fresh builder with empty {@code args} / {@code environment}, no working directory, no
   *     stdin, and a default timeout of 30 seconds
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Mutable builder for {@link ExecutionRequest}. */
  public static final class Builder {

    private Runtime runtime;
    private String script;
    private final List<String> args = new ArrayList<>();
    private Path workingDirectory;
    private Duration timeout = DEFAULT_TIMEOUT;
    private final Map<String, String> environment = new LinkedHashMap<>();
    private String stdin;

    private Builder() {}

    /**
     * Set the runtime. Required.
     *
     * @param runtime non-null runtime
     * @return this builder
     * @throws NullPointerException if {@code runtime} is null
     */
    public Builder withRuntime(Runtime runtime) {
      this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
      return this;
    }

    /**
     * Set the script body. Required.
     *
     * @param script non-blank script
     * @return this builder
     * @throws NullPointerException if {@code script} is null
     * @throws IllegalArgumentException if {@code script} is blank
     */
    public Builder withScript(String script) {
      this.script = Validate.notBlank("script", script);
      return this;
    }

    /**
     * Replace the script's positional args.
     *
     * @param args non-null list (may be empty)
     * @return this builder
     * @throws NullPointerException if {@code args} is null or contains null elements
     */
    public Builder withArgs(List<String> args) {
      Objects.requireNonNull(args, "args must not be null");
      for (var a : args) {
        Objects.requireNonNull(a, "args must not contain null");
      }
      this.args.clear();
      this.args.addAll(args);
      return this;
    }

    /**
     * Append a single positional arg.
     *
     * @param arg non-null arg
     * @return this builder
     * @throws NullPointerException if {@code arg} is null
     */
    public Builder withArg(String arg) {
      Objects.requireNonNull(arg, "arg must not be null");
      this.args.add(arg);
      return this;
    }

    /**
     * Set the working directory. {@code null} requests a per-invocation temp directory.
     *
     * @param workingDirectory nullable path
     * @return this builder
     */
    public Builder withWorkingDirectory(Path workingDirectory) {
      this.workingDirectory = workingDirectory;
      return this;
    }

    /**
     * Set the wall-clock timeout. Defaults to 30 seconds.
     *
     * @param timeout strictly positive duration
     * @return this builder
     * @throws NullPointerException if {@code timeout} is null
     * @throws IllegalArgumentException if {@code timeout} is zero or negative
     */
    public Builder withTimeout(Duration timeout) {
      this.timeout = Validate.positiveDuration("timeout", timeout);
      return this;
    }

    /**
     * Replace the environment map.
     *
     * @param environment non-null map (may be empty)
     * @return this builder
     * @throws NullPointerException if {@code environment} is null or contains null keys/values
     */
    public Builder withEnvironment(Map<String, String> environment) {
      Objects.requireNonNull(environment, "environment must not be null");
      for (var e : environment.entrySet()) {
        Objects.requireNonNull(e.getKey(), "environment must not contain null keys");
        Objects.requireNonNull(e.getValue(), "environment must not contain null values");
      }
      this.environment.clear();
      this.environment.putAll(environment);
      return this;
    }

    /**
     * Add a single environment variable.
     *
     * @param name non-blank var name
     * @param value non-null value
     * @return this builder
     * @throws NullPointerException if {@code name} or {@code value} is null
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public Builder withEnv(String name, String value) {
      Validate.notBlank("name", name);
      Objects.requireNonNull(value, "value must not be null");
      this.environment.put(name, value);
      return this;
    }

    /**
     * Set the stdin contents. {@code null} clears any previously set stdin so the child sees an
     * empty stream.
     *
     * @param stdin nullable stdin contents
     * @return this builder
     */
    public Builder withStdin(String stdin) {
      this.stdin = stdin;
      return this;
    }

    /**
     * Build the immutable request.
     *
     * @return the request
     * @throws IllegalStateException if {@code runtime} or {@code script} was never set
     */
    public ExecutionRequest build() {
      if (runtime == null) {
        throw new IllegalStateException("runtime is required");
      }
      if (script == null) {
        throw new IllegalStateException("script is required");
      }
      return new ExecutionRequest(
          runtime,
          script,
          args,
          workingDirectory,
          timeout,
          environment,
          Optional.ofNullable(stdin));
    }
  }
}
