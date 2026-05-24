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
import ai.singlr.repl.sandbox.policy.SandboxPolicySerialization;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

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
  private final SocketChannel rpcChannelSocket;
  private final Path socketDir;
  private final StringBuilder capturedStdout;
  private final Thread stdoutReader;
  private final Path ephemeralWorkingDir;

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
    this(process, transport, channel, config, null, null, null, null, null);
  }

  /**
   * Full constructor including the dedicated RPC socket, the stdout-capture thread, and the
   * per-session ephemeral working directory used by {@link #create(JvmSandboxConfig,
   * HostFunctionRegistry)}. The 4-arg constructor above is kept for tests that wire a {@link
   * ProcessTransport} directly over piped streams.
   *
   * @param rpcChannelSocket the accepted Unix-domain socket carrying RPC, or {@code null} if the
   *     transport is not socket-backed (test injection path)
   * @param socketDir the temp directory holding the socket file, deleted on {@link #close()}, or
   *     {@code null}
   * @param capturedStdout buffer the stdout reader thread appends to, or {@code null} when no
   *     reader is wired
   * @param stdoutReader virtual thread reading {@code process.getInputStream()} into {@code
   *     capturedStdout}, or {@code null}
   * @param ephemeralWorkingDir per-session scratch directory created by {@link #create} when the
   *     caller did not set {@link JvmSandboxConfig#workingDirectory()}, recursively deleted on
   *     {@link #close()}; {@code null} when the caller supplied an explicit working directory or
   *     when the test injection path skipped working-directory wiring entirely
   */
  JvmSandbox(
      Process process,
      ProcessTransport transport,
      RpcChannel channel,
      JvmSandboxConfig config,
      SocketChannel rpcChannelSocket,
      Path socketDir,
      StringBuilder capturedStdout,
      Thread stdoutReader,
      Path ephemeralWorkingDir) {
    this.process = process;
    this.transport = transport;
    this.channel = channel;
    this.config = config;
    this.rpcChannelSocket = rpcChannelSocket;
    this.socketDir = socketDir;
    this.capturedStdout = capturedStdout;
    this.stdoutReader = stdoutReader;
    this.ephemeralWorkingDir = ephemeralWorkingDir;
    this.shutdownHook = new Thread(this::destroyOnJvmShutdown, "jvm-sandbox-shutdown-hook");
    Runtime.getRuntime().addShutdownHook(shutdownHook);
  }

  /**
   * Accessor for the per-session ephemeral working directory created by {@link #create}, or {@code
   * null} when the caller pinned a working directory via {@link
   * JvmSandboxConfig#workingDirectory()}. Exposed for tests that verify cwd containment.
   */
  Path ephemeralWorkingDirForTests() {
    return ephemeralWorkingDir;
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
      // Snapshot descendants first — once the parent is killed, the OS reparents orphans to init
      // and process.descendants() returns empty (the kernel no longer sees them in this subtree).
      // The snapshot is followed immediately by the parent kill so the parent can't fork new
      // descendants during the sweep; if it does, the new fork escapes — but the parent is dead
      // within microseconds so the window is effectively closed.
      var descendantsSnapshot = process.descendants().toList();
      process.destroyForcibly();
      descendantsSnapshot.forEach(ProcessHandle::destroyForcibly);
    }
    if (socketDir != null) {
      deleteSocketDirQuietly(socketDir);
    }
    if (ephemeralWorkingDir != null) {
      deleteEphemeralCwdQuietly(ephemeralWorkingDir);
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
   * @param config the sandbox configuration
   * @param registry the host function registry
   * @return a running sandbox
   */
  static JvmSandbox create(JvmSandboxConfig config, HostFunctionRegistry registry) {
    Path socketDir = null;
    Path ephemeralCwd = null;
    ServerSocketChannel listener = null;
    Process process = null;
    SocketChannel acceptedRpc = null;
    Thread stdoutThread = null;
    try {
      // The RPC channel runs on a Unix domain socket bound in a private temp directory. The
      // subprocess connects on startup using the path passed via --rpc-socket; the host accepts
      // exactly one connection and then closes the listener (and deletes the socket file) so no
      // other process — including a JShell snippet inside the subprocess — can connect a second
      // time. Subprocess stdout stays for capture only and is never parsed as RPC, eliminating
      // the C1 stdout-RPC forgery vector where a snippet could write a `\0RPC:` frame to raw
      // FileDescriptor.out and reach the host's RPC dispatcher.
      socketDir = createPrivateSocketDir();
      var socketPath = socketDir.resolve("rpc.sock");
      listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
      listener.bind(UnixDomainSocketAddress.of(socketPath), 1);

      // Working-directory resolution. When the caller pinned a path via
      // JvmSandboxConfig.withWorkingDirectory(...), use it verbatim and don't track it for
      // deletion (caller-owned lifecycle). Otherwise create a private per-session scratch dir;
      // ephemeralCwd is tracked so close() / cleanup paths can delete it recursively. Either way
      // we set ProcessBuilder.directory() so the subprocess never inherits the host JVM's cwd.
      Path effectiveCwd;
      if (config.workingDirectory() != null) {
        effectiveCwd = config.workingDirectory();
      } else {
        ephemeralCwd = createEphemeralWorkingDir();
        effectiveCwd = ephemeralCwd;
      }

      var javaHome = System.getProperty("java.home");
      var javaBin = javaHome + "/bin/java";
      var pb = new ProcessBuilder(buildLaunchCommand(javaBin, config, socketPath.toString()));
      pb.directory(effectiveCwd.toFile());
      pb.redirectErrorStream(false);
      var env = pb.environment();
      env.clear();
      env.put("PATH", System.getenv().getOrDefault("PATH", ""));
      env.put("JAVA_HOME", javaHome);
      process = pb.start();
      // Host doesn't write to subprocess stdin; close it so any read in the subprocess hits EOF
      // immediately rather than blocking. RPC goes via the socket.
      try {
        process.getOutputStream().close();
      } catch (IOException ignored) {
        // Subprocess may have already closed it.
      }

      var listenerForAccept = listener;
      listener = null;
      acceptedRpc = acceptWithTimeout(listenerForAccept, config.subprocessStartupTimeout());
      Files.deleteIfExists(socketPath);

      var processTransport =
          new ProcessTransport(
              Channels.newInputStream(acceptedRpc), Channels.newOutputStream(acceptedRpc));
      var rpcChannel = new RpcChannel(processTransport, registry, config.callTimeout());

      var capturedStdout = new StringBuilder();
      stdoutThread = startStdoutReader(process, capturedStdout);

      var customPrelude = SandboxPrelude.synthesizeCustomWrappers(registry);
      registry.freeze();
      var sandbox =
          new JvmSandbox(
              process,
              processTransport,
              rpcChannel,
              config,
              acceptedRpc,
              socketDir,
              capturedStdout,
              stdoutThread,
              ephemeralCwd);
      if (!customPrelude.isBlank()) {
        installCustomPrelude(rpcChannel, customPrelude);
      }
      return sandbox;
    } catch (IOException e) {
      cleanupOnFailure(listener, process, acceptedRpc, stdoutThread, socketDir, ephemeralCwd);
      throw new ReplException("Failed to start JVM sandbox subprocess", e);
    } catch (RuntimeException e) {
      cleanupOnFailure(listener, process, acceptedRpc, stdoutThread, socketDir, ephemeralCwd);
      throw e;
    }
  }

  /**
   * Create a private temp directory the RPC socket lives in. Mode 0700 is set explicitly on POSIX
   * filesystems. On non-POSIX filesystems (Windows) the {@link UnsupportedOperationException} is
   * swallowed and the directory keeps whatever ACLs {@link Files#createTempDirectory} produced —
   * which has not been validated by Helios; deployers running on Windows must verify the temp-dir
   * ACL story for their JDK and filesystem before relying on cross-process isolation. The directory
   * is deleted by {@link #close()} along with its single socket file.
   */
  private static Path createPrivateSocketDir() throws IOException {
    var dir = Files.createTempDirectory("helios-rpc-");
    try {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException ignored) {
    }
    return dir;
  }

  /**
   * Create a private per-session working directory the subprocess JVM is launched into. Mode 0700
   * on POSIX filesystems for the same reason the socket dir is locked down — same-UID enumeration
   * of the cwd's contents is the residual threat the JShell snippet itself shouldn't be able to
   * carry out from inside, but reducing exposure to other processes on the host is cheap and
   * principled. The directory is deleted recursively by {@link #close()} (or {@link
   * #destroyOnJvmShutdown}) along with whatever transient files the snippet wrote.
   */
  private static Path createEphemeralWorkingDir() throws IOException {
    var dir = Files.createTempDirectory("helios-sandbox-cwd-");
    try {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException ignored) {
    }
    return dir;
  }

  /**
   * Accept exactly one inbound connection on {@code listener} or throw on timeout. Uses a virtual
   * thread so the calling thread retains its interrupt-status semantics and the timeout actually
   * fires (blocking {@code ServerSocketChannel#accept} has no built-in timeout in blocking mode).
   *
   * <p>Takes ownership of {@code listener}: closes it on every exit path (success or any failure
   * mode) before returning or throwing. Callers MUST NOT close the listener after this method
   * returns — doing so was the source of the pre-fix double-close in the timeout cleanup path.
   * Visible for testing.
   */
  static SocketChannel acceptWithTimeout(ServerSocketChannel listener, Duration timeout)
      throws IOException {
    var accept =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return listener.accept();
              } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
              }
            },
            r -> Thread.ofVirtual().name("helios-sandbox-rpc-accept").start(r));
    try {
      return accept.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new IOException(
          "Subprocess did not connect to the RPC socket within "
              + timeout
              + "; the launch"
              + " probably failed — check stderr for the cause",
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for subprocess RPC connect", e);
    } catch (ExecutionException e) {
      var cause = e.getCause();
      if (cause instanceof IOException io) {
        throw io;
      }
      throw new IOException("RPC accept failed", cause);
    } finally {
      try {
        listener.close();
      } catch (IOException ignored) {
      }
    }
  }

  /** Reader thread that drains the subprocess's stdout into a shared buffer for execute results. */
  private static Thread startStdoutReader(Process process, StringBuilder buffer) {
    return Thread.ofVirtual()
        .name("helios-sandbox-stdout-reader")
        .start(
            () -> {
              try (var reader =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  synchronized (buffer) {
                    if (!buffer.isEmpty()) {
                      buffer.append('\n');
                    }
                    buffer.append(line);
                  }
                }
              } catch (IOException ignored) {
                // Stream closed by subprocess termination; nothing to do.
              }
            });
  }

  private static void cleanupOnFailure(
      ServerSocketChannel listener,
      Process process,
      SocketChannel acceptedRpc,
      Thread stdoutThread,
      Path socketDir,
      Path ephemeralCwd) {
    if (listener != null) {
      try {
        listener.close();
      } catch (IOException ignored) {
        // best-effort
      }
    }
    if (acceptedRpc != null) {
      try {
        acceptedRpc.close();
      } catch (IOException ignored) {
        // best-effort
      }
    }
    if (process != null && process.isAlive()) {
      process.destroyForcibly();
    }
    if (stdoutThread != null) {
      stdoutThread.interrupt();
    }
    if (socketDir != null) {
      deleteSocketDirQuietly(socketDir);
    }
    if (ephemeralCwd != null) {
      deleteEphemeralCwdQuietly(ephemeralCwd);
    }
  }

  private static void deleteSocketDirQuietly(Path dir) {
    try (var entries = Files.list(dir)) {
      entries.forEach(
          p -> {
            try {
              Files.deleteIfExists(p);
            } catch (IOException ignored) {
              // best-effort
            }
          });
    } catch (IOException ignored) {
      // dir already gone, fine
    }
    try {
      Files.deleteIfExists(dir);
    } catch (IOException ignored) {
      // best-effort
    }
  }

  /**
   * Recursively delete the ephemeral working directory and every file the snippet wrote into it.
   * Symlinks are deleted as links (not followed) so a snippet that managed to {@code ln -s / leak}
   * from inside the sandbox cannot trick host cleanup into walking the root filesystem. Every
   * failure is swallowed — this runs from {@code close()} / the shutdown hook / the launch-failure
   * path, and re-throwing would mask the original outcome. A residual orphan dir under the system
   * temp area is the worst case; the OS's tmpwatch (or the deployer's housekeeping) will eventually
   * reap it.
   */
  static void deleteEphemeralCwdQuietly(Path dir) {
    if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try {
      Files.walkFileTree(
          dir,
          java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
          Integer.MAX_VALUE,
          new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(
                Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
              try {
                Files.deleteIfExists(file);
              } catch (IOException ignored) {
                // best-effort
              }
              return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
              // Unreadable entries (broken symlink target permissions, racing deletes) are
              // logged at FINE elsewhere; treat as already-gone for the cleanup walk.
              return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException exc) {
              try {
                Files.deleteIfExists(d);
              } catch (IOException ignored) {
                // best-effort
              }
              return java.nio.file.FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException ignored) {
      // best-effort
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
   *         <li>module-graph flags ({@code --add-modules}, {@code --limit-modules}) — Maven
   *             Surefire and similar test runners propagate {@code --add-modules=ALL-MODULE-PATH}
   *             which would re-add every module on the parent's module path and defeat any L3
   *             {@code --limit-modules} restriction the bootstrap applies. The bootstrap re-adds
   *             only what it provably needs ({@code ai.singlr.repl} via the explicit add-modules
   *             below; everything else flows through transitive resolution).
   *       </ul>
   *   <li>Parent's {@code java.class.path} as {@code -cp} — safe for both JPMS and non-JPMS
   *       parents. Non-JPMS parents rely on this entirely; JPMS parents have it sparse but correct.
   *   <li>{@code --add-modules ai.singlr.repl} when the parent uses {@code --module-path}, so the
   *       bootstrap module is a root module in the subprocess's boot layer.
   * </ul>
   */
  static List<String> buildLaunchCommand(String javaBin, JvmSandboxConfig config) {
    return buildLaunchCommand(javaBin, config, null);
  }

  /**
   * Build the subprocess command, optionally including the {@code --rpc-socket=<path>} argument the
   * {@link JvmSandboxBootstrap} parses to find the host-side Unix domain socket. {@code
   * rpcSocketPath == null} produces a command line equivalent to the pre-2.1.3 launch (subprocess
   * falls back to stdin/stdout RPC), which is still useful for unit tests of the command-builder
   * itself — not used by the production {@link #create} path.
   *
   * <p>A non-permissive {@link JvmSandboxConfig#sandboxPolicy()} is encoded via {@link
   * SandboxPolicySerialization#encode(ai.singlr.repl.sandbox.policy.SandboxPolicy)} and appended as
   * {@code --sandbox-policy=<encoded>}. A permissive policy is the bootstrap's own default, so it
   * is not propagated — keeping the command line stable for the common case.
   */
  static List<String> buildLaunchCommand(
      String javaBin, JvmSandboxConfig config, String rpcSocketPath) {
    var command = new ArrayList<String>();
    command.add(javaBin);
    command.add("-Xmx" + config.maxHeapMb() + "m");

    var parentArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
    for (var arg : parentArgs) {
      if (shouldPropagateJvmArg(arg)) {
        command.add(arg);
      }
    }

    var classpath =
        resolveClasspathForSubprocess(
            System.getProperty("java.class.path"), Path.of("").toAbsolutePath());
    if (!Strings.isBlank(classpath)) {
      command.add("-cp");
      command.add(classpath);
    }

    var modulepathLaunch = parentUsesModulePath(parentArgs);
    if (modulepathLaunch) {
      command.add("--add-modules");
      command.add("ai.singlr.repl");
    }

    var limitModules = config.subprocessModules().limitModulesArg(modulepathLaunch);
    if (!limitModules.isEmpty()) {
      command.add("--limit-modules");
      command.add(limitModules);
    }

    command.add("ai.singlr.repl.sandbox.JvmSandboxBootstrap");
    if (rpcSocketPath != null) {
      command.add("--rpc-socket=" + rpcSocketPath);
    }
    if (!config.sandboxPolicy().isPermissive()) {
      command.add("--sandbox-policy=" + SandboxPolicySerialization.encode(config.sandboxPolicy()));
    }
    return command;
  }

  /**
   * Resolve every entry in a {@code path.separator}-delimited classpath against the supplied host
   * cwd, returning entries as absolute paths. Absolute entries pass through verbatim; relative
   * entries are joined to {@code hostCwd} and normalised.
   *
   * <p>The subprocess sandbox switches its own working directory (to {@link
   * JvmSandboxConfig#workingDirectory()} when set, otherwise to a private {@code
   * /tmp/helios-sandbox-cwd-*}). Any relative entry in the host JVM's {@code java.class.path}
   * therefore cannot be resolved by the subprocess. This bites callers running as {@code java -jar
   * target/app.jar}: the JDK puts {@code "target/app.jar"} in {@code java.class.path}, the
   * subprocess can't find it from its new cwd, and dies with {@code ClassNotFoundException:
   * ai.singlr.repl.sandbox.JvmSandboxBootstrap}. The host then waits the full RPC accept timeout
   * for a connection that will never come.
   *
   * <p>Normalising to absolute paths against the host cwd reproduces the resolution the host JVM
   * already performed when it loaded its own classpath. Jars whose manifests carry a relative
   * {@code Class-Path:} continue to work because those entries are resolved relative to the jar's
   * location, not the JVM cwd.
   *
   * @param rawClasspath the raw {@code java.class.path} string; null or blank yields the input
   *     unchanged so the existing caller can branch on blank
   * @param hostCwd the host JVM's current working directory; non-null and must be absolute
   * @return the classpath with every entry resolved to an absolute path
   */
  static String resolveClasspathForSubprocess(String rawClasspath, Path hostCwd) {
    if (Strings.isBlank(rawClasspath)) {
      return rawClasspath;
    }
    var sep = System.getProperty("path.separator");
    var entries = rawClasspath.split(Pattern.quote(sep));
    var resolved = new ArrayList<String>(entries.length);
    for (var entry : entries) {
      if (entry.isEmpty()) {
        resolved.add(entry);
        continue;
      }
      var p = Path.of(entry);
      resolved.add(p.isAbsolute() ? entry : hostCwd.resolve(p).normalize().toString());
    }
    return String.join(sep, resolved);
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
    if (arg.startsWith("--add-modules") || arg.startsWith("--limit-modules")) {
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
      var stdout = drainCapturedStdout();
      return toExecutionResult(request.code(), result, stdout);
    } catch (RpcChannel.RpcException e) {
      var stdout = drainCapturedStdout();
      return ExecutionResult.newBuilder()
          .withExecutedCode(request.code())
          .withStdout(stdout)
          .withStderr(e.getMessage())
          .withExitCode(1)
          .build();
    }
  }

  /**
   * Drain accumulated subprocess stdout (the dedicated capture thread populates it) and clear the
   * buffer for the next execute. Falls back to {@link ProcessTransport#drainStdout()} when the
   * sandbox was constructed via the test-injection path with no socket-backed capture thread.
   */
  private String drainCapturedStdout() {
    if (capturedStdout == null) {
      return transport.drainStdout();
    }
    synchronized (capturedStdout) {
      var s = capturedStdout.toString();
      capturedStdout.setLength(0);
      return s;
    }
  }

  @Override
  public boolean isAlive() {
    return !closed.get() && process.isAlive();
  }

  /**
   * Tear the sandbox down: kill the subprocess and any descendants it spawned, close the RPC
   * channel and socket, join the stdout reader, and delete the private socket directory. Idempotent
   * — second and later calls are no-ops.
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
    if (closed.compareAndSet(false, true)) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException alreadyShuttingDown) {
      }
      var descendantsSnapshot = process.descendants().toList();
      process.destroyForcibly();
      descendantsSnapshot.forEach(ProcessHandle::destroyForcibly);
      try {
        process.waitFor(Duration.ofSeconds(5));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.log(Level.FINE, "Interrupted while waiting for sandbox process to exit", e);
      }
      channel.close();
      if (rpcChannelSocket != null) {
        try {
          rpcChannelSocket.close();
        } catch (IOException ignored) {
        }
      }
      if (stdoutReader != null) {
        try {
          stdoutReader.join(Duration.ofSeconds(2));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      if (socketDir != null) {
        deleteSocketDirQuietly(socketDir);
      }
      if (ephemeralWorkingDir != null) {
        deleteEphemeralCwdQuietly(ephemeralWorkingDir);
      }
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
