/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox;

import com.standardapplied.helios.repl.sandbox.policy.GuardedExecutionControlProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import jdk.jshell.JShell;

/**
 * JShell subprocess entry point. Connects to the host's RPC socket, evaluates the Java code the
 * host sends via JShell with {@link jdk.jshell.execution.LocalExecutionControl
 * LocalExecutionControl} behind the configured sandbox policy, and returns structured results. Host
 * function calls from sandbox code flow back through the same socket using reverse RPC.
 *
 * <p>Threading model:
 *
 * <ul>
 *   <li>Main thread runs {@link BootstrapRpc#readLoop()} — reads the socket, dispatches requests,
 *       routes responses
 *   <li>Virtual thread per execute — {@link SnippetEvaluator} captures stdout/stderr and runs the
 *       JShell eval on a platform thread in a thread group of its own, so a timeout can find every
 *       thread the snippet started
 *   <li>Sandbox code calling {@link HostBridge#predict} blocks on a {@link
 *       java.util.concurrent.CompletableFuture} until the main thread routes the host response
 * </ul>
 */
public final class JvmSandboxBootstrap {

  static final int UNSTOPPABLE_SNIPPET_EXIT_CODE = 3;

  private JvmSandboxBootstrap() {}

  /**
   * Subprocess entry point. Expects exactly one argument, {@code --rpc-socket=<path>}, identifying
   * the Unix domain socket the host bound for this sandbox. Connects on startup; the host accepts
   * exactly one connection and closes its listener immediately, so this connection is the only RPC
   * channel for the sandbox's lifetime. Subprocess stdin is unused (closed by the host) and
   * subprocess stdout/stderr stay attached to the OS pipes for incidental capture only — they are
   * never parsed as RPC.
   *
   * <p><strong>What C1 closes.</strong> The stdout-RPC forgery path: a snippet writing a {@code
   * \0RPC:} frame to raw {@code FileDescriptor.out} can no longer reach the host's dispatcher,
   * because the host parses RPC frames off the dedicated socket — not stdout — and the snippet has
   * no handle to that socket. This is the property the {@code
   * rawStdoutWriteDoesNotForgeAnRpcCallToHost} regression test pins down.
   *
   * <p><strong>What C1 does not close.</strong> The {@code realOut} {@code PrintStream} wrapping
   * the RPC socket is a private field of {@link HostBridgeState}. A snippet that can call {@code
   * setAccessible(true)} on that field grabs the same handle the legitimate dispatcher uses and
   * forges frames directly into the host. {@code setAccessible(true)} is gated by JPMS module
   * accessibility:
   *
   * <ul>
   *   <li><strong>JPMS launch</strong> (parent uses {@code --module-path}): {@code
   *       com.standardapplied.helios.repl} is a named module and the module-info does not {@code
   *       open com.standardapplied.helios.repl.sandbox} to any other module, so the reflective
   *       access throws {@code InaccessibleObjectException}. The reflection path is closed.
   *   <li><strong>Classpath launch</strong>: the class lives in an unnamed module, which is fully
   *       open to reflection; {@code setAccessible(true)} succeeds. The reflection forgery
   *       reproduces (see {@code reflectionForgesAnRpcCallToHost}).
   *   <li><strong>JPMS launch with {@code
   *       --add-opens=com.standardapplied.helios.repl/com.standardapplied.helios.repl.sandbox=...}
   *       inherited from the parent</strong>: equivalent to classpath launch for this gap. {@link
   *       SandboxLauncher#shouldPropagateJvmArg} forwards {@code --add-opens} into the subprocess,
   *       so any parent that opened the package — including common test runners and JVM
   *       instrumentation — leaks the open into the sandbox.
   * </ul>
   *
   * <p>Intra-JVM isolation between trusted bootstrap code and JShell-evaluated snippets is
   * fundamentally weak in any of those cases: anything reachable on the heap is reachable to a
   * sufficiently motivated snippet via reflection in the absence of a SecurityManager. Deployers
   * running untrusted snippet payloads are responsible for arranging OS-level isolation around the
   * host process (containers, namespaces, separate UIDs per session, seccomp profiles); the
   * intra-JVM defenses here (C1 stdout decoupling, the dedicated-socket lifecycle, this WARNING)
   * raise the bar and catch casual mistakes but are not a substitute for an external boundary.
   *
   * <p>At startup this method emits a one-time {@code WARNING} on stderr when the bootstrap is
   * running in an unnamed module, naming the reduced-isolation regime explicitly so deployers who
   * took a classpath launch by accident notice in development.
   */
  public static void main(String[] args) {
    BootstrapArguments.warnIfReducedIsolation(System.err);
    var socketPath = BootstrapArguments.parseRpcSocketArg(args);
    var policy = BootstrapArguments.parseSandboxPolicyArg(args);
    Duration stopGrace;
    try {
      stopGrace = BootstrapArguments.parseStopGraceArg(args);
    } catch (IllegalArgumentException e) {
      System.err.println("JvmSandboxBootstrap: " + e.getMessage());
      System.exit(2);
      return;
    }
    SocketChannel rpcSocket;
    try {
      rpcSocket = SocketChannel.open(StandardProtocolFamily.UNIX);
      rpcSocket.connect(UnixDomainSocketAddress.of(socketPath));
    } catch (IOException e) {
      System.err.println(
          "JvmSandboxBootstrap: failed to connect to RPC socket " + socketPath + ": " + e);
      System.exit(2);
      return;
    }

    var rpcOut = new PrintStream(Channels.newOutputStream(rpcSocket), true, StandardCharsets.UTF_8);
    var rpcIn =
        new BufferedReader(
            new InputStreamReader(Channels.newInputStream(rpcSocket), StandardCharsets.UTF_8));

    var jshell =
        JShell.builder()
            .executionEngine(new GuardedExecutionControlProvider(policy), Map.of())
            .build();
    addHostBridgeToJShellClasspath(jshell);
    jshell.eval("import static com.standardapplied.helios.repl.sandbox.HostBridge.*;");
    jshell.eval("import com.standardapplied.helios.repl.sandbox.HostBridge;");
    SandboxPrelude.install(jshell);

    var bridge = new HostBridgeState(rpcOut);
    var evaluator = new SnippetEvaluator(jshell, bridge, Thread::join, stopGrace);
    HostBridgeState.setInstance(bridge);

    new BootstrapRpc(rpcIn, bridge, evaluator, Runtime.getRuntime()::halt).readLoop();

    jshell.close();
    try {
      rpcSocket.close();
    } catch (IOException ignored) {
      // best-effort
    }
    System.exit(0);
  }

  /**
   * Make {@link HostBridge} (and the rest of {@code com.standardapplied.helios.repl}) visible to
   * JShell's compilation context. The sandbox subprocess is launched with {@code --add-modules
   * com.standardapplied.helios.repl} so the classes are on the boot layer at runtime — but JShell's
   * internal javac runs its own compilation unit that only sees explicit classpath entries. Without
   * this, sandbox code calling {@code predict(...)}, {@code fetch(...)}, or any other bridge method
   * fails to compile with {@code "cannot find symbol"}.
   */
  static void addHostBridgeToJShellClasspath(JShell jshell) {
    try {
      var codeSource = HostBridge.class.getProtectionDomain().getCodeSource();
      if (codeSource == null) {
        return;
      }
      var url = codeSource.getLocation();
      if (url == null) {
        return;
      }
      var path = Paths.get(url.toURI()).toString();
      jshell.addToClasspath(path);
    } catch (Exception e) {
      System.err.println(
          "Warning: could not add HostBridge location to JShell classpath: " + e.getMessage());
    }
  }
}
