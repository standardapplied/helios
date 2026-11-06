/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.tool;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.CancellationToken;
import com.standardapplied.helios.core.process.BinaryResolver;
import com.standardapplied.helios.core.process.BoundedProcess;
import com.standardapplied.helios.core.process.ProcessOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Host-side grant that lets an agent invoke a single CLI binary under tightly controlled
 * conditions. The grant pins the binary at build time, owns the environment (so secrets never leak
 * into the JVM-inherited environment of the child), filters argv, jails the working directory, and
 * caps output and runtime. Its env values are registered in the {@link SecretRegistry} of the
 * sessions that run it, and the session redacts the tool's output against that registry before
 * anything sees it.
 *
 * <h2>Security guarantees</h2>
 *
 * <ul>
 *   <li><b>Binary is pinned at build time.</b> {@code "gh"} is resolved against {@code PATH} once,
 *       the absolute path stored, and a later hostile {@code PATH} cannot shadow the binary at
 *       invocation time.
 *   <li><b>Always argv array, never shell.</b> The process is started through {@link
 *       BoundedProcess} with an explicit list — no {@code /bin/sh -c}, no shell metacharacter
 *       expansion.
 *   <li><b>Environment is cleared then injected.</b> The child does not inherit the JVM's
 *       environment. Only secrets/PATH the operator explicitly granted are visible.
 *   <li><b>Argv pre-scan refuses secrets in argv.</b> A registered secret value appearing in any
 *       argv slot fails the call before the process starts; secrets must arrive via env-only.
 *   <li><b>Output is redacted by the session.</b> The grant must be built with the registry passed
 *       to {@code SessionOptions.withSecretRegistry}; run in a session with any other registry, the
 *       tool refuses without starting a process. A stream cut at the output cap loses the start of
 *       a secret the cut went through, so the session's redaction cannot miss it.
 *   <li><b>Stdin is empty.</b> The child's stdin is closed immediately after fork.
 *   <li><b>Per-call jail by default.</b> If no working directory is set, a fresh temporary
 *       directory is created per call and removed on exit.
 *   <li><b>Process tree is reaped on timeout, cancellation and interrupt.</b> Descendants are
 *       destroyed alongside the direct child.
 * </ul>
 *
 * <h2>Typical use</h2>
 *
 * <pre>{@code
 * import com.standardapplied.helios.session.tools.ToolBinding;
 * import com.standardapplied.helios.session.tools.ToolCategory;
 *
 * var registry = new SecretRegistry();
 * var gh = CommandGrant.builder("gh")
 *     .withSecretRegistry(registry)
 *     .withEnv("GH_TOKEN", System.getenv("GH_TOKEN"))
 *     .withTimeout(Duration.ofSeconds(30))
 *     .withMaxOutputBytes(50_000)
 *     .withArgValidator(args -> args.isEmpty() || !"auth".equals(args.get(0))
 *         ? Optional.empty()
 *         : Optional.of("'gh auth' is not allowed via this grant"))
 *     .build();
 *
 * var options = SessionOptions.newBuilder()
 *     .withModel(model)
 *     .withSecretRegistry(registry)
 *     .withTool(ToolBinding.newBuilder(gh.toTool()).withCategory(ToolCategory.EXECUTION).build())
 *     .build();
 * }</pre>
 */
public final class CommandGrant {

  private static final String DEFAULT_PATH = "/usr/local/bin:/usr/bin:/bin";
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
  private static final int DEFAULT_MAX_OUTPUT_BYTES = 50_000;
  private static final int DEFAULT_MAX_CONCURRENT = 4;

  private final String toolName;
  private final String description;
  private final Path binaryPath;
  private final Map<String, String> env;
  private final Map<String, String> environment;
  private final Path cwd;
  private final Duration timeout;
  private final int maxOutputBytes;
  private final Function<List<String>, Optional<String>> argValidator;
  private final boolean stderrToModel;
  private final SecretRegistry secretRegistry;
  private final Semaphore concurrency;

  private CommandGrant(Builder b, Path binaryPath, SecretRegistry registry) {
    this.toolName = b.toolName != null ? b.toolName : binaryPath.getFileName().toString();
    this.description =
        b.description != null
            ? b.description
            : "Invoke the " + binaryPath.getFileName() + " command-line tool";
    this.binaryPath = binaryPath;
    this.env = Map.copyOf(b.env);
    this.environment = childEnvironment(b.path != null ? b.path : DEFAULT_PATH, b.env);
    this.cwd = b.cwd;
    this.timeout = b.timeout;
    this.maxOutputBytes = b.maxOutputBytes;
    this.argValidator = b.argValidator;
    this.stderrToModel = b.stderrToModel;
    this.secretRegistry = registry;
    this.concurrency = new Semaphore(b.maxConcurrent);
  }

  private static Map<String, String> childEnvironment(String path, Map<String, String> env) {
    var environment = new LinkedHashMap<String, String>();
    environment.put("PATH", path);
    environment.putAll(env);
    return Collections.unmodifiableMap(environment);
  }

  /**
   * Start a builder that will run the given binary. {@code spec} may be an absolute path or a
   * basename to be resolved against {@code PATH}. Nothing is resolved until {@link
   * Builder#build()}.
   *
   * @param spec an absolute path to an executable, or a basename with no path separator
   * @return a new builder with every setting at its default
   */
  public static Builder builder(String spec) {
    return new Builder(spec);
  }

  /**
   * The model-visible tool name.
   *
   * @return the name set with {@link Builder#withName(String)}, or the binary's basename
   */
  public String name() {
    return toolName;
  }

  /**
   * The absolute path the binary was resolved to at build time; every invocation runs this file.
   *
   * @return the pinned absolute path
   */
  public Path binaryPath() {
    return binaryPath;
  }

  /**
   * The {@link SecretRegistry} the grant registered its env values in. The tool runs only in a
   * session whose registry is this same instance.
   *
   * @return the registry passed to {@link Builder#withSecretRegistry(SecretRegistry)}
   */
  public SecretRegistry secretRegistry() {
    return secretRegistry;
  }

  /**
   * Wrap this grant as a {@link Tool} that the model can invoke. The tool exposes a single {@code
   * args} parameter (array of strings).
   *
   * @return a new tool named {@link #name()}, sharing this grant's concurrency limit with every
   *     other tool made from it
   */
  public Tool toTool() {
    return new CommandGrantTool(this, description, stderrToModel).toTool();
  }

  /**
   * Invoke the bound binary with {@code userArgs}. The output is raw; the session redacts it.
   *
   * @param cancellation the call's token; firing it kills the child
   * @throws RejectedException if argv validation fails or argv contains a registered secret
   * @throws CancellationException if {@code cancellation} fired before the child ended on its own
   * @throws InterruptedException if the calling thread is interrupted while waiting
   * @throws IOException if the subprocess cannot be started
   */
  InvocationResult invoke(List<String> userArgs, CancellationToken cancellation)
      throws InterruptedException, IOException {
    var argsList = List.copyOf(userArgs);
    checkArgs(argsList);
    if (!concurrency.tryAcquire()) {
      throw new RejectedException(
          "Concurrency limit reached for " + toolName + "; try again later");
    }
    try {
      return run(argsList, cancellation);
    } finally {
      concurrency.release();
    }
  }

  private void checkArgs(List<String> argsList) {
    if (argValidator != null) {
      var rejection = argValidator.apply(argsList);
      if (rejection.isPresent()) {
        throw new RejectedException(rejection.get());
      }
    }
    for (var arg : argsList) {
      if (secretRegistry.leaks(arg)) {
        throw new RejectedException(
            "Argument contains a registered secret value; secrets must be passed via env, not"
                + " argv");
      }
    }
  }

  private InvocationResult run(List<String> userArgs, CancellationToken cancellation)
      throws IOException, InterruptedException {
    var argv = new ArrayList<String>(userArgs.size() + 1);
    argv.add(binaryPath.toString());
    argv.addAll(userArgs);
    ProcessOutcome outcome;
    try (var process =
        BoundedProcess.newBuilder(argv)
            .withEnvironment(environment)
            .withWorkingDirectory(cwd)
            .withTempDirectoryPrefix("helios-grant-")
            .withTimeout(timeout)
            .withMaxOutputBytes(maxOutputBytes)
            .withSecretRegistry(secretRegistry)
            .start()) {
      process.stdin().close();
      outcome = process.await(cancellation);
    }
    if (!outcome.timedOut() && cancellation.isCancelled()) {
      throw new CancellationException("invocation of " + toolName + " cancelled");
    }
    return new InvocationResult(
        outcome.exitCode(),
        new String(outcome.stdout(), StandardCharsets.UTF_8),
        new String(outcome.stderr(), StandardCharsets.UTF_8),
        outcome.timedOut(),
        outcome.truncated());
  }

  /**
   * Outcome of a single subprocess invocation. The text is raw; the session redacts it.
   *
   * @param exitCode the process exit code, or {@code -1} on timeout
   * @param stdout stdout text (UTF-8 decoded)
   * @param stderr stderr text (UTF-8 decoded)
   * @param timedOut true if the process was killed because it exceeded the configured timeout
   * @param truncated true if stdout or stderr exceeded {@code maxOutputBytes} and was clipped
   */
  record InvocationResult(
      int exitCode, String stdout, String stderr, boolean timedOut, boolean truncated) {}

  /** Thrown when a call is rejected before subprocess start (validation or quota). */
  static final class RejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    RejectedException(String message) {
      super(message);
    }
  }

  /** Builder for {@link CommandGrant}. */
  public static final class Builder {
    private final String spec;
    private String toolName;
    private String description;
    private final Map<String, String> env = new LinkedHashMap<>();
    private String path;
    private Path cwd;
    private Duration timeout = DEFAULT_TIMEOUT;
    private int maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
    private Function<List<String>, Optional<String>> argValidator;
    private boolean stderrToModel = false;
    private SecretRegistry secretRegistry;
    private int maxConcurrent = DEFAULT_MAX_CONCURRENT;

    private Builder(String spec) {
      this.spec = spec;
    }

    /**
     * Override the tool name exposed to the model. Defaults to the binary basename.
     *
     * @param name the tool name; {@code null} restores the default
     * @return this builder
     */
    public Builder withName(String name) {
      this.toolName = name;
      return this;
    }

    /**
     * Override the tool description exposed to the model. Defaults to {@code "Invoke the <basename>
     * command-line tool"}.
     *
     * @param description what the tool does, as the model reads it; {@code null} restores the
     *     default
     * @return this builder
     */
    public Builder withDescription(String description) {
      this.description = description;
      return this;
    }

    /**
     * Add an environment variable for the child process. The value is also registered in the {@link
     * SecretRegistry} under the same name, so the session redacts it from every tool's output.
     * Adding a name again replaces its value. The registry's rules on a secret value apply at
     * {@link #build()}.
     *
     * @param name the variable name, also the secret's name in redaction markers; non-blank
     * @param value the variable's value; non-null
     * @return this builder
     * @throws IllegalArgumentException if {@code name} is {@code null} or blank, or {@code value}
     *     is {@code null}
     */
    public Builder withEnv(String name, String value) {
      if (Strings.isBlank(name)) {
        throw new IllegalArgumentException("Env var name must not be blank");
      }
      if (value == null) {
        throw new IllegalArgumentException("Env var value must not be null");
      }
      env.put(name, value);
      return this;
    }

    /**
     * Override the {@code PATH} env var passed to the child. Defaults to {@code
     * /usr/local/bin:/usr/bin:/bin}. A {@code PATH} set through {@link #withEnv(String, String)}
     * takes precedence. Does not affect how {@link #build()} resolves the binary itself.
     *
     * @param path the child's search path; {@code null} restores the default
     * @return this builder
     */
    public Builder withPath(String path) {
      this.path = path;
      return this;
    }

    /**
     * Set the working directory for invocations. If not set, a fresh temporary directory is created
     * per call and removed on exit.
     *
     * @param cwd the directory every invocation starts in; {@code null} restores the per-call
     *     temporary directory
     * @return this builder
     */
    public Builder withCwd(Path cwd) {
      this.cwd = cwd;
      return this;
    }

    /**
     * Maximum wall-clock duration for any invocation; past it the process tree is killed and the
     * call reports a timeout. Defaults to 30 seconds.
     *
     * @param timeout the limit; strictly positive
     * @return this builder
     * @throws IllegalArgumentException if {@code timeout} is {@code null}, zero or negative
     */
    public Builder withTimeout(Duration timeout) {
      if (timeout == null || timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("Timeout must be positive");
      }
      this.timeout = timeout;
      return this;
    }

    /**
     * Cap on captured stdout and stderr (each, in bytes). Output past the cap is dropped and a
     * truncation marker is appended. Defaults to 50,000 bytes.
     *
     * @param bytes the per-stream cap in bytes; at least 1,024
     * @return this builder
     * @throws IllegalArgumentException if {@code bytes} is below 1,024
     */
    public Builder withMaxOutputBytes(int bytes) {
      if (bytes < 1024) {
        throw new IllegalArgumentException("maxOutputBytes must be at least 1024");
      }
      this.maxOutputBytes = bytes;
      return this;
    }

    /**
     * Validate argv before exec. Return {@link Optional#empty()} to accept; return a reason to
     * reject. The pre-scan that refuses argv carrying a registered secret always runs after this
     * validator. Unset by default: every argv is accepted.
     *
     * @param validator receives the call's argument list (without the binary) and returns the
     *     rejection reason the model sees, or empty to accept; {@code null} removes the validator
     * @return this builder
     */
    public Builder withArgValidator(Function<List<String>, Optional<String>> validator) {
      this.argValidator = validator;
      return this;
    }

    /**
     * Whether to expose the child's stderr to the model. Defaults to false.
     *
     * @param include {@code true} to include stderr in the tool's output, {@code false} to hide it
     * @return this builder
     */
    public Builder withStderrToModel(boolean include) {
      this.stderrToModel = include;
      return this;
    }

    /**
     * The {@link SecretRegistry} of the sessions that will run this grant, the one passed to {@code
     * SessionOptions.withSecretRegistry}. Required: {@link #build()} registers the env values in
     * it, and the tool refuses to run in a session with a different registry.
     *
     * @param registry the sessions' registry; non-null
     * @return this builder
     * @throws NullPointerException if {@code registry} is {@code null}
     */
    public Builder withSecretRegistry(SecretRegistry registry) {
      this.secretRegistry = Objects.requireNonNull(registry, "registry");
      return this;
    }

    /**
     * Maximum number of concurrent invocations of this grant. Defaults to 4. A call beyond it is
     * refused at once, never queued.
     *
     * @param n the limit; at least 1
     * @return this builder
     * @throws IllegalArgumentException if {@code n} is below 1
     */
    public Builder withMaxConcurrent(int n) {
      if (n < 1) {
        throw new IllegalArgumentException("maxConcurrent must be at least 1");
      }
      this.maxConcurrent = n;
      return this;
    }

    /**
     * Resolve the binary, register the env values in the registry and build the grant. A basename
     * is resolved against the host JVM's {@code PATH}, not the one {@link #withPath(String)} gives
     * the child.
     *
     * @return the grant, its binary pinned
     * @throws IllegalStateException if {@link #withSecretRegistry(SecretRegistry)} was not called,
     *     or the binary is not executable or not found on {@code PATH}
     * @throws IllegalArgumentException if the spec is {@code null}, blank or a relative path with a
     *     separator, or an env value fails {@link SecretRegistry#register(String, String)}
     */
    public CommandGrant build() {
      if (secretRegistry == null) {
        throw new IllegalStateException(
            "CommandGrant "
                + spec
                + " needs withSecretRegistry(registry): pass the SecretRegistry of the sessions"
                + " that will run it");
      }
      var resolved = BinaryResolver.resolve(spec, System.getenv("PATH"));
      for (var entry : env.entrySet()) {
        secretRegistry.register(entry.getKey(), entry.getValue());
      }
      return new CommandGrant(this, resolved, secretRegistry);
    }
  }

  /**
   * Names of env vars granted to the child (for diagnostics; values not exposed).
   *
   * @return the unmodifiable set of names given to {@link Builder#withEnv(String, String)}, without
   *     the {@code PATH} the grant adds itself
   */
  public Set<String> envVarNames() {
    return env.keySet();
  }
}
