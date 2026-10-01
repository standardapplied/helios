/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.tool;

import ai.singlr.core.common.RedactionResult;
import ai.singlr.core.common.Redactor;
import ai.singlr.core.common.SecretRegistry;
import ai.singlr.core.common.Strings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Host-side grant that lets an agent invoke a single CLI binary under tightly controlled
 * conditions. The grant pins the binary at build time, owns the environment (so secrets never leak
 * into the JVM-inherited environment of the child), filters argv, jails the working directory, caps
 * output and runtime, and pipes everything through a {@link Redactor} so registered secret values
 * cannot reach the model-visible string.
 *
 * <h2>Security guarantees</h2>
 *
 * <ul>
 *   <li><b>Binary is pinned at build time.</b> {@code "gh"} is resolved against {@code PATH} once,
 *       the absolute path stored, and a later hostile {@code PATH} cannot shadow the binary at
 *       invocation time.
 *   <li><b>Always argv array, never shell.</b> {@link ProcessBuilder} is invoked with an explicit
 *       list — no {@code /bin/sh -c}, no shell metacharacter expansion.
 *   <li><b>Environment is cleared then injected.</b> The child does not inherit the JVM's
 *       environment. Only secrets/PATH the operator explicitly granted are visible.
 *   <li><b>Argv pre-scan refuses secrets in argv.</b> A registered secret value appearing in any
 *       argv slot fails the call before {@link ProcessBuilder#start()}; secrets must arrive via
 *       env-only.
 *   <li><b>Output redaction is mandatory.</b> Both stdout and stderr are scrubbed against the
 *       {@link SecretRegistry} before they leave the host process.
 *   <li><b>Stdin is empty.</b> The child's stdin is closed immediately after fork.
 *   <li><b>Per-call jail by default.</b> If no working directory is set, a fresh temporary
 *       directory is created per call and removed on exit.
 *   <li><b>Process tree is reaped on timeout.</b> Descendants are destroyed alongside the direct
 *       child.
 * </ul>
 *
 * <h2>Typical use</h2>
 *
 * <pre>{@code
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
 * agent.tools().add(gh.toTool());
 * }</pre>
 */
public final class CommandGrant {

  private static final String DEFAULT_PATH = "/usr/local/bin:/usr/bin:/bin";
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
  private static final int DEFAULT_MAX_OUTPUT_BYTES = 50_000;
  private static final int DEFAULT_MAX_CONCURRENT = 4;
  private static final byte[] TRUNCATION_MARKER =
      "\n[truncated: output exceeded cap]".getBytes(StandardCharsets.US_ASCII);

  private final String toolName;
  private final String description;
  private final Path binaryPath;
  private final Map<String, String> env;
  private final String path;
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
    this.path = b.path != null ? b.path : DEFAULT_PATH;
    this.cwd = b.cwd;
    this.timeout = b.timeout;
    this.maxOutputBytes = b.maxOutputBytes;
    this.argValidator = b.argValidator;
    this.stderrToModel = b.stderrToModel;
    this.secretRegistry = registry;
    this.concurrency = new Semaphore(b.maxConcurrent);
  }

  /**
   * Start a builder that will run the given binary. {@code spec} may be an absolute path or a
   * basename to be resolved against {@code PATH}.
   */
  public static Builder builder(String spec) {
    return new Builder(spec);
  }

  /** The model-visible tool name (defaults to the binary's basename). */
  public String name() {
    return toolName;
  }

  /** The pinned absolute path to the binary. */
  public Path binaryPath() {
    return binaryPath;
  }

  /** The {@link SecretRegistry} this grant redacts against. Shared if the operator passed one. */
  public SecretRegistry secretRegistry() {
    return secretRegistry;
  }

  /**
   * Wrap this grant as a {@link Tool} that the model can invoke. The tool exposes a single {@code
   * args} parameter (array of strings).
   */
  public Tool toTool() {
    return Tool.newBuilder()
        .withName(toolName)
        .withDescription(description)
        .withParameter(
            ToolParameter.newBuilder()
                .withName("args")
                .withType(ParameterType.ARRAY)
                .withDescription(
                    "Arguments passed to %s (excluding the binary itself)"
                        .formatted(binaryPath.getFileName()))
                .withRequired(true)
                .withItems(ToolParameter.newBuilder().withType(ParameterType.STRING).build())
                .build())
        .withExecutor(this::executeAsTool)
        .build();
  }

  private ToolResult executeAsTool(Map<String, Object> args, ToolContext ctx) {
    ctx.cancellation().throwIfCancelled();
    var raw = args.get("args");
    if (!(raw instanceof List<?> list)) {
      return ToolResult.failure("Parameter 'args' is required and must be an array of strings");
    }
    var argv = new ArrayList<String>(list.size());
    for (var entry : list) {
      if (!(entry instanceof String s)) {
        return ToolResult.failure("Every entry in 'args' must be a string");
      }
      argv.add(s);
    }
    InvocationResult result;
    try {
      result = invoke(argv);
    } catch (RejectedException e) {
      return ToolResult.failure(e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ToolResult.failure("Interrupted while invoking " + toolName);
    } catch (IOException e) {
      return ToolResult.failure("I/O error invoking " + toolName + ": " + e.getMessage());
    }
    var sb = new StringBuilder();
    sb.append("[exit ").append(result.exitCode());
    if (result.timedOut()) {
      sb.append(" TIMEOUT");
    }
    if (result.truncated()) {
      sb.append(" TRUNCATED");
    }
    sb.append("]\n");
    sb.append(result.stdout());
    if (stderrToModel && !result.stderr().isEmpty()) {
      sb.append("\n[stderr]\n").append(result.stderr());
    }
    return ToolResult.success(sb.toString(), result);
  }

  /**
   * Invoke the bound binary with {@code userArgs}. Output is redacted before being returned.
   *
   * @throws RejectedException if argv validation fails or argv contains a registered secret
   * @throws InterruptedException if the calling thread is interrupted while waiting
   * @throws IOException if the subprocess cannot be started
   */
  public InvocationResult invoke(List<String> userArgs) throws InterruptedException, IOException {
    var argsList = List.copyOf(userArgs);
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
    if (!concurrency.tryAcquire()) {
      throw new RejectedException(
          "Concurrency limit reached for " + toolName + "; try again later");
    }
    Path effectiveCwd = null;
    boolean ownCwd = false;
    try {
      if (cwd != null) {
        effectiveCwd = cwd;
      } else {
        effectiveCwd = Files.createTempDirectory("helios-grant-");
        ownCwd = true;
      }
      return runProcess(argsList, effectiveCwd);
    } finally {
      concurrency.release();
      if (ownCwd) {
        deleteRecursively(effectiveCwd);
      }
    }
  }

  private InvocationResult runProcess(List<String> userArgs, Path effectiveCwd)
      throws IOException, InterruptedException {
    var argv = new ArrayList<String>(userArgs.size() + 1);
    argv.add(binaryPath.toString());
    argv.addAll(userArgs);
    var pb = new ProcessBuilder(argv);
    pb.environment().clear();
    pb.environment().put("PATH", path);
    pb.environment().putAll(env);
    pb.directory(effectiveCwd.toFile());
    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
    var startNanos = System.nanoTime();
    var proc = pb.start();
    proc.getOutputStream().close();
    var stdoutSink = new BoundedSink(maxOutputBytes);
    var stderrSink = new BoundedSink(maxOutputBytes);
    var t1 = Thread.startVirtualThread(() -> drain(proc.getInputStream(), stdoutSink));
    var t2 = Thread.startVirtualThread(() -> drain(proc.getErrorStream(), stderrSink));
    var exited = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    var timedOut = !exited;
    if (timedOut) {
      proc.destroy();
      proc.descendants().forEach(ProcessHandle::destroy);
      if (!proc.waitFor(2, TimeUnit.SECONDS)) {
        proc.destroyForcibly();
        proc.descendants().forEach(ProcessHandle::destroyForcibly);
        proc.waitFor(1, TimeUnit.SECONDS);
      }
    }
    t1.join();
    t2.join();
    var elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
    var exitCode = timedOut ? -1 : proc.exitValue();
    var redactor = secretRegistry.redactor();
    var stdoutResult = redactor.redact(stdoutSink.bytes());
    var stderrResult = redactor.redact(stderrSink.bytes());
    var counts = mergeCounts(stdoutResult, stderrResult);
    return new InvocationResult(
        exitCode,
        stdoutResult.text(),
        stderrResult.text(),
        timedOut,
        stdoutSink.truncated() || stderrSink.truncated(),
        elapsed,
        counts);
  }

  private static Map<String, Integer> mergeCounts(RedactionResult a, RedactionResult b) {
    if (a.counts().isEmpty() && b.counts().isEmpty()) {
      return Map.of();
    }
    var merged = new LinkedHashMap<String, Integer>();
    a.counts().forEach((k, v) -> merged.merge(k, v, Integer::sum));
    b.counts().forEach((k, v) -> merged.merge(k, v, Integer::sum));
    return Collections.unmodifiableMap(merged);
  }

  private static void drain(InputStream in, BoundedSink sink) {
    var buf = new byte[8192];
    try (in) {
      int n;
      while ((n = in.read(buf)) >= 0) {
        sink.write(buf, 0, n);
      }
    } catch (IOException ignored) {
      // Stream closed by process termination.
    }
  }

  private static void deleteRecursively(Path root) {
    try (var stream = Files.walk(root)) {
      stream
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException ignored) {
                  // Best-effort cleanup.
                }
              });
    } catch (IOException ignored) {
      // Best-effort cleanup.
    }
  }

  private static Path resolveBinary(String spec) {
    return resolveBinary(spec, System.getenv("PATH"));
  }

  /**
   * Resolve {@code spec} against {@code pathEnv}, returning the absolute, executable binary path.
   * Accepts either an absolute path (which is checked for executability) or a basename (which is
   * looked up against the supplied {@code pathEnv}, using {@link java.io.File#pathSeparator} to
   * split entries).
   *
   * <p>Exposed for reuse by other subprocess primitives that also need pin-at-build-time semantics
   * (e.g. {@code LocalProcessExecutionProvider} in {@code helios-session}). The lookup is
   * deliberately deterministic — no caching, no fallbacks beyond the supplied {@code PATH}.
   *
   * @param spec absolute path or basename; non-blank
   * @param pathEnv the {@code PATH} environment variable to search; non-null, non-empty for
   *     basename lookups
   * @return the absolute path to an executable file
   * @throws IllegalArgumentException if {@code spec} is blank or contains separators without being
   *     absolute
   * @throws IllegalStateException if the binary cannot be located on the supplied {@code PATH}
   */
  public static Path resolveBinary(String spec, String pathEnv) {
    if (Strings.isBlank(spec)) {
      throw new IllegalArgumentException("Binary spec must not be blank");
    }
    var direct = Path.of(spec);
    if (direct.isAbsolute()) {
      if (!Files.isRegularFile(direct) || !Files.isExecutable(direct)) {
        throw new IllegalStateException("Binary not executable at " + direct);
      }
      return direct.toAbsolutePath();
    }
    if (spec.contains(File.separator)) {
      throw new IllegalArgumentException(
          "Binary spec must be an absolute path or a basename (no separators): " + spec);
    }
    if (pathEnv == null || pathEnv.isEmpty()) {
      throw new IllegalStateException("PATH is empty; cannot resolve '" + spec + "'");
    }
    for (var dir : pathEnv.split(File.pathSeparator)) {
      if (dir.isEmpty()) {
        continue;
      }
      var candidate = Path.of(dir, spec);
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
        return candidate.toAbsolutePath();
      }
    }
    throw new IllegalStateException("Binary '" + spec + "' not found on PATH");
  }

  /**
   * Outcome of a single subprocess invocation. Strings have already been redacted against the
   * {@link SecretRegistry}.
   *
   * @param exitCode the process exit code, or {@code -1} on timeout
   * @param stdout redacted stdout text (UTF-8 decoded)
   * @param stderr redacted stderr text (UTF-8 decoded)
   * @param timedOut true if the process was killed because it exceeded the configured timeout
   * @param truncated true if stdout or stderr exceeded {@code maxOutputBytes} and was clipped
   * @param duration wall-clock time the process spent running, including reaping
   * @param redactionCounts per-secret-name redaction counts across both streams
   */
  public record InvocationResult(
      int exitCode,
      String stdout,
      String stderr,
      boolean timedOut,
      boolean truncated,
      Duration duration,
      Map<String, Integer> redactionCounts) {

    /** Total redactions across stdout and stderr. */
    public int totalRedactions() {
      var total = 0;
      for (var c : redactionCounts.values()) {
        total += c;
      }
      return total;
    }
  }

  /** Thrown when a call is rejected before subprocess start (validation or quota). */
  public static final class RejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RejectedException(String message) {
      super(message);
    }
  }

  private static final class BoundedSink {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final int max;
    private boolean truncated;

    BoundedSink(int max) {
      this.max = max;
    }

    synchronized void write(byte[] buf, int off, int len) {
      var remaining = max - out.size();
      if (remaining <= 0) {
        truncated = true;
        return;
      }
      var toWrite = Math.min(len, remaining);
      out.write(buf, off, toWrite);
      if (toWrite < len) {
        truncated = true;
      }
    }

    synchronized byte[] bytes() {
      var raw = out.toByteArray();
      if (!truncated) {
        return raw;
      }
      var combined = new byte[raw.length + TRUNCATION_MARKER.length];
      System.arraycopy(raw, 0, combined, 0, raw.length);
      System.arraycopy(TRUNCATION_MARKER, 0, combined, raw.length, TRUNCATION_MARKER.length);
      return combined;
    }

    boolean truncated() {
      return truncated;
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

    /** Override the tool name exposed to the model. Defaults to the binary basename. */
    public Builder withName(String name) {
      this.toolName = name;
      return this;
    }

    /** Override the tool description exposed to the model. */
    public Builder withDescription(String description) {
      this.description = description;
      return this;
    }

    /**
     * Add an environment variable for the child process. The value is also registered in the {@link
     * SecretRegistry} under the same name, so it will be redacted from any model-visible output
     * produced by this grant or any other tool sharing the same registry.
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

    /** Override the {@code PATH} env var passed to the child. Defaults to a sane minimal path. */
    public Builder withPath(String path) {
      this.path = path;
      return this;
    }

    /**
     * Set the working directory for invocations. If not set, a fresh temporary directory is created
     * per call and removed on exit.
     */
    public Builder withCwd(Path cwd) {
      this.cwd = cwd;
      return this;
    }

    /** Maximum wall-clock duration for any invocation. Defaults to 30 seconds. */
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
     * validator.
     */
    public Builder withArgValidator(Function<List<String>, Optional<String>> validator) {
      this.argValidator = validator;
      return this;
    }

    /** Whether to expose the child's stderr to the model. Defaults to false. */
    public Builder withStderrToModel(boolean include) {
      this.stderrToModel = include;
      return this;
    }

    /**
     * Use the supplied {@link SecretRegistry} so this grant participates in cross-tool redaction.
     * If not called, the grant gets a private registry that other tools will not see.
     */
    public Builder withSecretRegistry(SecretRegistry registry) {
      this.secretRegistry = registry;
      return this;
    }

    /** Maximum number of concurrent invocations of this grant. Defaults to 4. */
    public Builder withMaxConcurrent(int n) {
      if (n < 1) {
        throw new IllegalArgumentException("maxConcurrent must be at least 1");
      }
      this.maxConcurrent = n;
      return this;
    }

    public CommandGrant build() {
      var resolved = resolveBinary(spec);
      var registry = secretRegistry != null ? secretRegistry : new SecretRegistry();
      for (var entry : env.entrySet()) {
        registry.register(entry.getKey(), entry.getValue());
      }
      return new CommandGrant(this, resolved, registry);
    }
  }

  /** Names of env vars granted to the child (for diagnostics; values not exposed). */
  public Set<String> envVarNames() {
    return env.keySet();
  }
}
