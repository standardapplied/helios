/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import ai.singlr.core.common.Strings;
import ai.singlr.repl.ReplException;
import ai.singlr.repl.host.HostFunctionRegistry;
import ai.singlr.repl.protocol.ProcessTransport;
import ai.singlr.repl.protocol.RpcChannel;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JVM subprocess sandbox. Launches a child JVM process that reads JSON-RPC execute requests on
 * stdin and returns results on stdout. Host function calls from the sandbox flow back through the
 * same channel.
 *
 * <p>The subprocess is started with the {@code repl-bootstrap} module (designed separately). For
 * unit testing, a mock process can be injected via the package-private constructor.
 */
public final class JvmSandbox implements Sandbox {

  private static final Logger LOG = Logger.getLogger(JvmSandbox.class.getName());

  private final Process process;
  private final ProcessTransport transport;
  private final RpcChannel channel;
  private final JvmSandboxConfig config;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Thread shutdownHook;

  /**
   * Create a sandbox wrapping an existing process. Used by the factory method and for testing.
   *
   * @param process the subprocess
   * @param transport the transport over the subprocess streams
   * @param channel the RPC channel
   * @param config the sandbox configuration
   */
  JvmSandbox(
      Process process, ProcessTransport transport, RpcChannel channel, JvmSandboxConfig config) {
    this.process = process;
    this.transport = transport;
    this.channel = channel;
    this.config = config;
    this.shutdownHook = new Thread(this::destroyOnJvmShutdown, "jvm-sandbox-shutdown-hook");
    Runtime.getRuntime().addShutdownHook(shutdownHook);
  }

  /**
   * Called from the JVM shutdown hook installed in the constructor. Force-terminates the subprocess
   * if the caller forgot to call {@link #close()}. Package-private for direct invocation from
   * tests.
   */
  void destroyOnJvmShutdown() {
    if (closed.get()) {
      return;
    }
    if (process.isAlive()) {
      // Kill the parent first so it cannot fork new children during the descendant sweep.
      process.destroyForcibly();
      process.descendants().forEach(ProcessHandle::destroyForcibly);
    }
  }

  /**
   * Create a JVM sandbox factory with the given configuration.
   *
   * @param config the sandbox configuration
   * @return a factory that creates JVM sandboxes
   */
  public static SandboxFactory factory(JvmSandboxConfig config) {
    if (config == null) {
      throw new IllegalArgumentException("Config must not be null");
    }
    return registry -> create(config, registry);
  }

  /**
   * Create a JVM sandbox factory with default configuration.
   *
   * @return a factory that creates JVM sandboxes
   */
  public static SandboxFactory factory() {
    return factory(JvmSandboxConfig.defaults());
  }

  /**
   * Create and start a new JVM subprocess sandbox.
   *
   * @param config the sandbox configuration
   * @param registry the host function registry
   * @return a running sandbox
   */
  static JvmSandbox create(JvmSandboxConfig config, HostFunctionRegistry registry) {
    try {
      var javaHome = System.getProperty("java.home");
      var javaBin = javaHome + "/bin/java";
      var pb = new ProcessBuilder(buildLaunchCommand(javaBin, config));
      pb.redirectErrorStream(false);
      var env = pb.environment();
      env.clear();
      env.put("PATH", System.getenv().getOrDefault("PATH", ""));
      env.put("JAVA_HOME", javaHome);
      var process = pb.start();
      var processTransport =
          new ProcessTransport(process.getInputStream(), process.getOutputStream());
      var rpcChannel = new RpcChannel(processTransport, registry, config.callTimeout());
      var customPrelude = SandboxPrelude.synthesizeCustomWrappers(registry);
      registry.freeze();
      var sandbox = new JvmSandbox(process, processTransport, rpcChannel, config);
      if (!customPrelude.isBlank()) {
        installCustomPrelude(rpcChannel, customPrelude);
      }
      return sandbox;
    } catch (IOException e) {
      throw new ReplException("Failed to start JVM sandbox subprocess", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static void installCustomPrelude(RpcChannel channel, String snippet) {
    Object response;
    try {
      response = channel.call("installPrelude", Map.of("snippet", snippet));
    } catch (RpcChannel.RpcException e) {
      throw new ReplException(
          "Failed to install custom host-function wrappers in sandbox: " + e.getMessage(), e);
    }
    if (response instanceof Map<?, ?> m) {
      var success = m.get("success");
      if (Boolean.FALSE.equals(success)) {
        var errors = m.get("errors") instanceof List<?> es ? es : List.of();
        throw new ReplException("Sandbox rejected custom host-function wrappers: " + errors);
      }
    }
  }

  /**
   * Build the subprocess command line. Inherits the parent JVM's input arguments so the subprocess
   * resolves modules the same way the parent does — critical for JPMS projects where the {@code
   * ai.singlr.repl} module lives on {@code --module-path}, not {@code -cp}. Without this, the
   * subprocess starts with a sparse classpath (just {@code java.class.path}) and dies with {@code
   * NoClassDefFoundError} on {@link JvmSandboxBootstrap}.
   *
   * <p>Inheritance rules:
   *
   * <ul>
   *   <li>Everything from {@link ManagementFactory#getRuntimeMXBean()}'s input args EXCEPT:
   *       <ul>
   *         <li>heap sizes ({@code -Xmx}, {@code -Xms}) — we set our own via {@code
   *             config.maxHeapMb()}
   *         <li>agent attachments ({@code -javaagent}, {@code -agentlib}, {@code -agentpath}) —
   *             inheriting a parent's debugger or profiler would break or deadlock
   *         <li>system properties ({@code -D...}) — the parent may carry secrets (auth tokens,
   *             trust-store passwords) in system properties; propagating them to the sandbox
   *             subprocess would expose them to JShell-evaluated user code via {@code
   *             System.getProperties()}
   *       </ul>
   *   <li>Parent's {@code java.class.path} as {@code -cp} — safe for both JPMS and non-JPMS
   *       parents. Non-JPMS parents rely on this entirely; JPMS parents have it sparse but correct.
   *   <li>{@code --add-modules ai.singlr.repl} when the parent uses {@code --module-path}, so the
   *       bootstrap module is a root module in the subprocess's boot layer.
   * </ul>
   */
  static List<String> buildLaunchCommand(String javaBin, JvmSandboxConfig config) {
    var command = new ArrayList<String>();
    command.add(javaBin);
    command.add("-Xmx" + config.maxHeapMb() + "m");

    var parentArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
    for (var arg : parentArgs) {
      if (shouldPropagateJvmArg(arg)) {
        command.add(arg);
      }
    }

    var classpath = System.getProperty("java.class.path");
    if (!Strings.isBlank(classpath)) {
      command.add("-cp");
      command.add(classpath);
    }

    if (parentUsesModulePath(parentArgs)) {
      command.add("--add-modules");
      command.add("ai.singlr.repl");
    }

    command.add("ai.singlr.repl.sandbox.JvmSandboxBootstrap");
    return command;
  }

  static boolean shouldPropagateJvmArg(String arg) {
    if (arg.startsWith("-Xmx") || arg.startsWith("-Xms")) {
      return false;
    }
    if (arg.startsWith("-javaagent:")
        || arg.startsWith("-agentlib:")
        || arg.startsWith("-agentpath:")) {
      return false;
    }
    if (arg.startsWith("-D")) {
      return false;
    }
    return true;
  }

  static boolean parentUsesModulePath(List<String> parentArgs) {
    for (var arg : parentArgs) {
      if (arg.equals("--module-path")
          || arg.startsWith("--module-path=")
          || arg.equals("-p")
          || arg.startsWith("-p=")) {
        return true;
      }
    }
    return false;
  }

  @Override
  public ExecutionResult execute(ExecutionRequest request) {
    return execute(request, ExecuteParams.DEFAULT);
  }

  @Override
  public ExecutionResult execute(ExecutionRequest request, ExecuteParams executeParams) {
    if (!isAlive()) {
      return ExecutionResult.failure("Sandbox process is not alive");
    }
    var timeout = request.timeout() != null ? request.timeout() : config.executionTimeout();
    try {
      var params = new java.util.LinkedHashMap<String, Object>();
      params.put("code", request.code());
      params.put("language", request.language());
      params.put("timeoutMs", timeout.toMillis());
      params.put("captureBindings", executeParams.captureBindings());
      params.put("maxBindingValueChars", executeParams.maxBindingValueChars());
      params.put("maxBindingSnapshotChars", executeParams.maxBindingSnapshotChars());
      var result = channel.call("execute", params);
      var stdout = transport.drainStdout();
      return toExecutionResult(request.code(), result, stdout);
    } catch (RpcChannel.RpcException e) {
      var stdout = transport.drainStdout();
      return ExecutionResult.newBuilder()
          .withExecutedCode(request.code())
          .withStdout(stdout)
          .withStderr(e.getMessage())
          .withExitCode(1)
          .build();
    }
  }

  @Override
  public boolean isAlive() {
    return !closed.get() && process.isAlive();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException alreadyShuttingDown) {
        // JVM is already shutting down; the hook will run (or has run) and that's fine.
      }
      // Destroy the subprocess FIRST so its stdout pipe closes on the OS side — only then
      // will the reader thread's blocking readLine() return with EOF. If we closed the
      // transport first, reader.close() could deadlock waiting for a read() that's pinned in a
      // native call. Once the subprocess is dead, transport.close() is a clean no-op wind-down.
      //
      // Order matters: kill the parent first so it cannot fork new descendants during the sweep,
      // then walk its surviving descendants. Snippets can call Runtime.exec(...) — without this
      // walk, orphaned grandchildren survive the sandbox.
      process.destroyForcibly();
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      try {
        process.waitFor(Duration.ofSeconds(5));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.log(Level.FINE, "Interrupted while waiting for sandbox process to exit", e);
      }
      channel.close();
    }
  }

  /** Access the underlying process (for testing). */
  Process process() {
    return process;
  }

  /** Access the transport (for testing). */
  ProcessTransport transport() {
    return transport;
  }

  /** Access the RPC channel (for testing). */
  RpcChannel channel() {
    return channel;
  }

  @SuppressWarnings("unchecked")
  private static ExecutionResult toExecutionResult(
      String executedCode, Object result, String capturedStdout) {
    if (result instanceof Map<?, ?> map) {
      var stdout = map.get("stdout") instanceof String s ? s : "";
      var stderr = map.get("stderr") instanceof String s ? s : "";
      var exitCode = map.get("exitCode") instanceof Number n ? n.intValue() : 0;
      var submitted = map.get("submitted");
      Map<String, String> bindings = Map.of();
      if (map.get("bindings") instanceof Map<?, ?> raw) {
        var b = new java.util.LinkedHashMap<String, String>();
        for (var entry : raw.entrySet()) {
          if (entry.getKey() instanceof String key) {
            b.put(key, String.valueOf(entry.getValue()));
          }
        }
        bindings = Map.copyOf(b);
      }
      var combinedStdout =
          capturedStdout.isEmpty()
              ? stdout
              : stdout.isEmpty() ? capturedStdout : capturedStdout + "\n" + stdout;
      return new ExecutionResult(
          executedCode, combinedStdout, stderr, exitCode, submitted, bindings);
    }
    return new ExecutionResult(
        executedCode,
        capturedStdout.isEmpty() ? String.valueOf(result) : capturedStdout,
        "",
        0,
        null,
        Map.of());
  }
}
