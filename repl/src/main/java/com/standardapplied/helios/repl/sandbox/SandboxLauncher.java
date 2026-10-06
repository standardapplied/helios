/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.repl.sandbox.policy.SandboxPolicySerialization;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Starts and ends the sandbox subprocess JVM. The command line reproduces the host JVM's own launch
 * (its module path or class path, minus what must not reach the sandbox), adds the {@code
 * --limit-modules} restriction and the bootstrap's arguments, and runs with an empty environment in
 * the sandbox's working directory.
 */
final class SandboxLauncher {

  private static final String BOOTSTRAP_MAIN_CLASS =
      "com.standardapplied.helios.repl.sandbox.JvmSandboxBootstrap";

  private SandboxLauncher() {}

  /**
   * Start the subprocess in {@code workingDirectory}, told to connect to {@code socketPath}. The
   * environment holds only {@code PATH} and {@code JAVA_HOME}. The host never writes to the
   * subprocess's stdin, so it is closed at once: a read in the subprocess hits end-of-stream rather
   * than blocking.
   */
  static Process start(JvmSandboxConfig config, Path socketPath, Path workingDirectory)
      throws IOException {
    var javaHome = System.getProperty("java.home");
    var pb =
        new ProcessBuilder(
            buildLaunchCommand(javaHome + "/bin/java", config, socketPath.toString()));
    pb.directory(workingDirectory.toFile());
    pb.redirectErrorStream(false);
    var env = pb.environment();
    env.clear();
    env.put("PATH", System.getenv().getOrDefault("PATH", ""));
    env.put("JAVA_HOME", javaHome);
    var process = pb.start();
    try {
      process.getOutputStream().close();
    } catch (IOException ignored) {
    }
    return process;
  }

  /**
   * Kill {@code process} and every descendant it has. Descendants are snapshotted before the parent
   * is killed: once it is dead the OS reparents them to init and {@link Process#descendants()} no
   * longer sees them. A descendant forked between the snapshot and the kill escapes; the parent is
   * dead within microseconds, so the window is effectively closed.
   */
  static void destroyTree(Process process) {
    var descendants = process.descendants().toList();
    process.destroyForcibly();
    descendants.forEach(ProcessHandle::destroyForcibly);
  }

  /**
   * Build the subprocess command line. Inherits the parent JVM's input arguments so the subprocess
   * resolves modules the same way the parent does — critical for JPMS projects where the {@code
   * com.standardapplied.helios.repl} module lives on {@code --module-path}, not {@code -cp}.
   * Without this, the subprocess starts with a sparse classpath (just {@code java.class.path}) and
   * dies with {@code NoClassDefFoundError} on {@link JvmSandboxBootstrap}.
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
   *             only what it provably needs ({@code com.standardapplied.helios.repl} via the
   *             explicit add-modules below; everything else flows through transitive resolution).
   *       </ul>
   *   <li>Parent's {@code java.class.path} as {@code -cp} — safe for both JPMS and non-JPMS
   *       parents. Non-JPMS parents rely on this entirely; JPMS parents have it sparse but correct.
   *   <li>{@code --add-modules com.standardapplied.helios.repl} when the parent uses {@code
   *       --module-path}, so the bootstrap module is a root module in the subprocess's boot layer.
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
   * itself — not used by the production {@link #start} path.
   *
   * <p>A non-permissive {@link JvmSandboxConfig#sandboxPolicy()} is encoded via {@link
   * SandboxPolicySerialization#encode(com.standardapplied.helios.repl.sandbox.policy.SandboxPolicy)}
   * and appended as {@code --sandbox-policy=<encoded>}. A permissive policy is the bootstrap's own
   * default, so it is not propagated — keeping the command line stable for the common case. {@link
   * JvmSandboxConfig#stopGrace()} always travels, as {@code --stop-grace=<ISO-8601 duration>}.
   */
  static List<String> buildLaunchCommand(
      String javaBin, JvmSandboxConfig config, String rpcSocketPath) {
    return buildLaunchCommand(
        javaBin,
        config,
        rpcSocketPath,
        ManagementFactory.getRuntimeMXBean().getInputArguments(),
        System.getProperty("java.class.path"),
        Path.of("").toAbsolutePath());
  }

  /**
   * The launch command for a host JVM started with {@code parentArgs} and {@code rawClasspath} from
   * {@code hostCwd}.
   */
  static List<String> buildLaunchCommand(
      String javaBin,
      JvmSandboxConfig config,
      String rpcSocketPath,
      List<String> parentArgs,
      String rawClasspath,
      Path hostCwd) {
    var command = new ArrayList<String>();
    command.add(javaBin);
    command.add("-Xmx" + config.maxHeapMb() + "m");
    parentArgs.stream().filter(SandboxLauncher::shouldPropagateJvmArg).forEach(command::add);
    var classpath = resolveClasspathForSubprocess(rawClasspath, hostCwd);
    if (!Strings.isBlank(classpath)) {
      command.add("-cp");
      command.add(classpath);
    }
    var modulepathLaunch = parentUsesModulePath(parentArgs);
    if (modulepathLaunch) {
      command.add("--add-modules");
      command.add(SubprocessModules.BOOTSTRAP_MODULE);
    }
    var limitModules = config.subprocessModules().limitModulesArg(modulepathLaunch);
    if (!limitModules.isEmpty()) {
      command.add("--limit-modules");
      command.add(limitModules);
    }
    command.add(BOOTSTRAP_MAIN_CLASS);
    if (rpcSocketPath != null) {
      command.add("--rpc-socket=" + rpcSocketPath);
    }
    command.add(BootstrapArguments.STOP_GRACE_ARG + config.stopGrace());
    if (!config.sandboxPolicy().enforcesNothing()) {
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
   * com.standardapplied.helios.repl.sandbox.JvmSandboxBootstrap}. The host then waits the full RPC
   * accept timeout for a connection that will never come.
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
    if (arg.startsWith("--enable-native-access")) {
      return false;
    }
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
}
