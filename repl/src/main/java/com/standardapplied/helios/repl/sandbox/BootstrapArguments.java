/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import com.standardapplied.helios.repl.sandbox.policy.SandboxPolicy;
import com.standardapplied.helios.repl.sandbox.policy.SandboxPolicySerialization;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * The sandbox subprocess's command-line arguments, as {@link SandboxLauncher} writes them, and the
 * warning the subprocess prints when it runs with reduced isolation. An argument the bootstrap
 * cannot run without, or cannot trust, ends the subprocess with exit code 2.
 */
final class BootstrapArguments {

  static final String STOP_GRACE_ARG = "--stop-grace=";

  private BootstrapArguments() {}

  /**
   * Emit a single {@code WARNING} line on the provided stream when the bootstrap is running in an
   * unnamed module (classpath launch) or when its package has been opened to all unnamed modules
   * (typically via {@code --add-opens} inherited from a test runner or instrumentation parent). In
   * either regime the {@code realOut} RPC socket reachable through {@code setAccessible(true)}; the
   * WARNING surfaces the reduced isolation so deployers don't take it on accident.
   */
  static void warnIfReducedIsolation(PrintStream err) {
    warnIfReducedIsolation(JvmSandboxBootstrap.class.getModule(), err);
  }

  /**
   * The {@link #warnIfReducedIsolation(PrintStream)} decision for the bootstrap running in {@code
   * module}, so each launch regime can be checked from a single JVM.
   */
  static void warnIfReducedIsolation(Module module, PrintStream err) {
    String reason;
    if (!module.isNamed()) {
      reason = "running in the unnamed module (classpath launch)";
    } else if (isSandboxPackageOpenToUnnamedModules(module)) {
      reason =
          "running in module "
              + module.getName()
              + " but package com.standardapplied.helios.repl.sandbox is opened to unnamed modules"
              + " (typically via --add-opens inherited from the parent JVM)";
    } else {
      return;
    }
    err.println(
        "WARNING: com.standardapplied.helios.repl JvmSandboxBootstrap is "
            + reason
            + ". A JShell snippet can use setAccessible(true) on private bootstrap fields to"
            + " obtain the RPC socket PrintStream and forge calls into the host. C1 closes the"
            + " stdout-RPC forgery path only; closing the reflection forgery path requires both"
            + " JPMS isolation (modulepath launch, no --add-opens to com.standardapplied.helios.repl.sandbox) AND"
            + " an externally-arranged OS-level isolation boundary around the host process for"
            + " untrusted workloads. See the JvmSandboxBootstrap#main javadoc for the full"
            + " isolation regime.");
  }

  /**
   * Detect whether {@code com.standardapplied.helios.repl.sandbox} is open to the unnamed module of
   * some classloader — the regime in which JShell-evaluated snippets, which live in their own
   * classloader's unnamed module, can call {@code setAccessible(true)} on the bootstrap's private
   * fields. {@link Module#isOpen(String)} checks only unconditional opens, so it misses {@code
   * --add-opens=...=ALL-UNNAMED}; the two-argument overload with an unnamed-module probe catches
   * it.
   */
  private static boolean isSandboxPackageOpenToUnnamedModules(Module module) {
    var probe = ClassLoader.getPlatformClassLoader().getUnnamedModule();
    return module.isOpen("com.standardapplied.helios.repl.sandbox", probe);
  }

  /**
   * Parse the optional {@code --sandbox-policy=<encoded>} argument. The host omits the flag when
   * the configured policy is {@link SandboxPolicy#permissive() permissive}, so a missing flag means
   * "permissive" — equivalent to no L2 policy layer. A present-but-malformed value is fatal (exit
   * code 2) rather than silently degrading to permissive: under-enforcing without telling anyone is
   * worse than refusing to launch.
   */
  static SandboxPolicy parseSandboxPolicyArg(String[] args) {
    for (var arg : args) {
      if (arg.startsWith("--sandbox-policy=")) {
        var encoded = arg.substring("--sandbox-policy=".length());
        try {
          return SandboxPolicySerialization.decode(encoded);
        } catch (IllegalArgumentException e) {
          System.err.println(
              "JvmSandboxBootstrap: malformed --sandbox-policy argument: " + e.getMessage());
          System.exit(2);
          throw new IllegalStateException("unreachable");
        }
      }
    }
    return SandboxPolicy.permissive();
  }

  /**
   * Parse the optional {@code --stop-grace=<ISO-8601 duration>} argument, how long a stopped
   * snippet has to end before the sandbox exits. The host always passes {@link
   * JvmSandboxConfig#stopGrace()}; absent means {@link JvmSandboxConfig#DEFAULT_STOP_GRACE}.
   *
   * @throws IllegalArgumentException if the value is not a positive duration
   */
  static Duration parseStopGraceArg(String[] args) {
    for (var arg : args) {
      if (arg.startsWith(STOP_GRACE_ARG)) {
        return positiveDuration(arg.substring(STOP_GRACE_ARG.length()));
      }
    }
    return JvmSandboxConfig.DEFAULT_STOP_GRACE;
  }

  private static Duration positiveDuration(String value) {
    Duration duration;
    try {
      duration = Duration.parse(value);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(STOP_GRACE_ARG + value + " is not a duration", e);
    }
    if (duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException(STOP_GRACE_ARG + value + " is not positive");
    }
    return duration;
  }

  /**
   * Parse the mandatory {@code --rpc-socket=<path>} argument. Failing fast with exit code 2 if
   * absent or malformed: the bootstrap has no usable fallback once stdout is no longer the RPC
   * channel.
   */
  static Path parseRpcSocketArg(String[] args) {
    for (var arg : args) {
      if (arg.startsWith("--rpc-socket=")) {
        return Path.of(arg.substring("--rpc-socket=".length()));
      }
    }
    System.err.println(
        "JvmSandboxBootstrap: missing required --rpc-socket=<path> argument. The sandbox host"
            + " (JvmSandbox) is responsible for binding the socket and passing the path; if you"
            + " are seeing this manually, you are running the bootstrap outside its intended"
            + " harness.");
    System.exit(2);
    throw new IllegalStateException("unreachable");
  }
}
