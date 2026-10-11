/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox;

import com.standardapplied.helios.repl.ReplException;
import com.standardapplied.helios.repl.host.HostFunctionRegistry;
import com.standardapplied.helios.repl.protocol.ProcessTransport;
import com.standardapplied.helios.repl.protocol.RpcChannel;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JVM subprocess sandbox. Launches a child JVM process that reads JSON-RPC execute requests over a
 * private Unix-domain socket and returns results on it. Host function calls from the sandbox flow
 * back through the same channel.
 *
 * <p>The subprocess runs {@link JvmSandboxBootstrap}. For unit testing, a transport over any
 * process's streams can be injected via the package-private constructor.
 */
public final class JvmSandbox implements Sandbox {

  private static final Logger LOG = Logger.getLogger(JvmSandbox.class.getName());

  private static final Duration EXIT_GRACE = Duration.ofSeconds(5);

  private static final Duration STDOUT_GRACE = Duration.ofSeconds(2);

  private final Process process;
  private final SandboxRpc rpc;
  private final JvmSandboxConfig config;
  private final StdoutCapture stdout;
  private final SandboxDirectories directories;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Thread shutdownHook;

  /**
   * Create a sandbox over a transport wired directly to {@code process}'s streams, with no socket
   * and no directories of its own. Used by tests.
   *
   * @param process the subprocess
   * @param transport the transport over the subprocess streams
   * @param channel the RPC channel
   * @param config the sandbox configuration
   */
  JvmSandbox(
      Process process, ProcessTransport transport, RpcChannel channel, JvmSandboxConfig config) {
    this(
        process,
        new SandboxRpc(transport, channel, null),
        config,
        StdoutCapture.of(transport),
        SandboxDirectories.NONE);
  }

  /** Create a sandbox over what {@link #create} launched; package-private for tests. */
  JvmSandbox(
      Process process,
      SandboxRpc rpc,
      JvmSandboxConfig config,
      StdoutCapture stdout,
      SandboxDirectories directories) {
    this.process = process;
    this.rpc = rpc;
    this.config = config;
    this.stdout = stdout;
    this.directories = directories;
    this.shutdownHook = new Thread(this::destroyOnJvmShutdown, "jvm-sandbox-shutdown-hook");
    Runtime.getRuntime().addShutdownHook(shutdownHook);
  }

  /**
   * The directories {@link #create} made for this sandbox: its RPC socket directory and, unless the
   * caller pinned one via {@link JvmSandboxConfig#workingDirectory()}, its ephemeral working
   * directory. Exposed for tests that verify containment and cleanup.
   */
  SandboxDirectories directoriesForTests() {
    return directories;
  }

  /**
   * Called from the JVM shutdown hook installed in the constructor. Force-terminates the subprocess
   * tree and deletes the sandbox's directories if the caller forgot to call {@link #close()}.
   * Package-private for direct invocation from tests.
   */
  void destroyOnJvmShutdown() {
    if (closed.get()) {
      return;
    }
    if (process.isAlive()) {
      SandboxLauncher.destroyTree(process);
    }
    directories.delete();
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
   * <p><strong>Same-UID isolation assumption.</strong> The RPC channel rides on a Unix-domain
   * socket inside a private 0700 directory, which blocks cross-UID attackers from enumerating or
   * connecting to the socket. It does NOT defend against a process running under the same UID as
   * the Helios host: between {@code listener.bind} and the subprocess's {@code connect}, a same-UID
   * attacker that polls the temp directory can race the legitimate subprocess and win the single
   * backlog slot, after which the host treats the attacker's connection as the sandbox RPC channel
   * and the real subprocess fails to connect. Helios currently assumes the deployer arranges
   * per-session UIDs (or any other OS-level mechanism that prevents same-UID coresidence with
   * untrusted workloads — containers, namespaces, per-tenant service accounts). A future change
   * will close the race authoritatively via Panama-based {@code SO_PEERCRED} (Linux) / {@code
   * LOCAL_PEERCRED} (BSD/macOS) verification on accept; until then, do not run Helios alongside
   * untrusted workloads under a shared UID.
   *
   * <p>If any step fails, what the earlier steps acquired is released, latest first.
   *
   * @param config the sandbox configuration
   * @param registry the host function registry
   * @return a running sandbox
   */
  static JvmSandbox create(JvmSandboxConfig config, HostFunctionRegistry registry) {
    var acquired = new ArrayDeque<Runnable>();
    try {
      var directories = SandboxDirectories.create(config.workingDirectory());
      acquired.push(directories::delete);
      Process process;
      SandboxRpc rpc;
      try (var listener = SandboxRpc.listen(directories.socketPath())) {
        process =
            SandboxLauncher.start(config, directories.socketPath(), directories.workingDirectory());
        acquired.push(process::destroyForcibly);
        rpc =
            SandboxRpc.accept(
                listener,
                directories.socketPath(),
                config.subprocessStartupTimeout(),
                registry,
                config.callTimeout());
      }
      acquired.push(rpc::closeSocket);
      var stdout = StdoutCapture.start(process);
      acquired.push(stdout::interrupt);
      var customPrelude = SandboxPrelude.synthesizeCustomWrappers(registry);
      registry.freeze();
      var sandbox = new JvmSandbox(process, rpc, config, stdout, directories);
      rpc.installPrelude(customPrelude);
      return sandbox;
    } catch (IOException e) {
      acquired.forEach(Runnable::run);
      throw new ReplException("Failed to start JVM sandbox subprocess", e);
    } catch (RuntimeException e) {
      acquired.forEach(Runnable::run);
      throw e;
    }
  }

  @Override
  public ExecutionResult execute(ExecutionRequest request) {
    return execute(request, ExecuteParams.DEFAULT);
  }

  @Override
  public ExecutionResult execute(ExecutionRequest request, ExecuteParams executeParams) {
    if (!isAlive()) {
      return ExecutionReplies.notAlive(closed.get(), process);
    }
    var budget = request.timeout() != null ? request.timeout() : config.executionTimeout();
    var params = new LinkedHashMap<String, Object>();
    params.put("code", request.code());
    params.put("language", request.language());
    params.put("timeoutMs", budget.toMillis());
    params.put("captureBindings", executeParams.captureBindings());
    params.put("maxBindingValueChars", executeParams.maxBindingValueChars());
    params.put("maxBindingSnapshotChars", executeParams.maxBindingSnapshotChars());
    var wait = config.executeReplyWait(budget);
    try {
      var reply = rpc.channel().call("execute", params, wait);
      return ExecutionReplies.toExecutionResult(request.code(), reply, stdout.drain());
    } catch (RpcChannel.RpcTimeoutException e) {
      close();
      return ExecutionReplies.unanswered(request.code(), stdout.drain(), wait);
    } catch (RpcChannel.RpcException e) {
      return ExecutionReplies.rpcFailure(request.code(), stdout.drain(), e.getMessage());
    }
  }

  @Override
  public boolean isAlive() {
    return !closed.get() && process.isAlive();
  }

  /**
   * Tear the sandbox down: kill the subprocess and any descendants it spawned, close the RPC
   * channel and socket, join the stdout reader, and delete the private socket directory and the
   * ephemeral working directory. Each step runs even if an earlier one throws; the first failure is
   * rethrown once every step has run, with any later ones suppressed on it. Idempotent — second and
   * later calls are no-ops.
   *
   * <p>Two limitations the caller should be aware of:
   *
   * <ul>
   *   <li><strong>Descendant kill races concurrent forks.</strong> Descendants are snapshotted
   *       before the parent is killed (after-kill the OS reparents to init and the snapshot goes
   *       empty), then the snapshot is destroyed alongside the parent. A snippet inside a tight
   *       {@link Runtime#exec} fork loop can spawn new descendants in the microsecond window
   *       between snapshot and parent kill; those escape. Within a single JVM there is no portable
   *       defense — bounding runaway descendant creation is the deployer's responsibility,
   *       typically via cgroup pids.max or an external process supervisor.
   *   <li><strong>Stdout reader join is best-effort.</strong> The reader's {@code readLine} returns
   *       EOF when the subprocess's stdout pipe closes, which it does once {@link
   *       Process#destroyForcibly} takes effect. A subprocess stuck in uninterruptible kernel sleep
   *       (D-state) keeps the pipe open until the kernel unblocks it; the join then times out and
   *       the reader virtual thread leaks. Bounded to one orphan thread per stuck session — does
   *       not compound across sessions.
   * </ul>
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    List<Runnable> steps =
        List.of(
            () -> {
              try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
              } catch (IllegalStateException alreadyShuttingDown) {
              }
            },
            () -> SandboxLauncher.destroyTree(process),
            () -> {
              try {
                process.waitFor(EXIT_GRACE);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.log(Level.FINE, "Interrupted while waiting for sandbox process to exit", e);
              }
            },
            rpc.channel()::close,
            rpc::closeSocket,
            () -> stdout.awaitEnd(STDOUT_GRACE),
            directories::delete);
    var failures = new ArrayList<RuntimeException>();
    for (var step : steps) {
      try {
        step.run();
      } catch (RuntimeException e) {
        failures.add(e);
      }
    }
    if (!failures.isEmpty()) {
      var first = failures.getFirst();
      failures.stream().skip(1).forEach(first::addSuppressed);
      throw first;
    }
  }

  /** Access the underlying process (for testing). */
  Process process() {
    return process;
  }

  /** Access the RPC connection (for testing). */
  SandboxRpc rpc() {
    return rpc;
  }
}
