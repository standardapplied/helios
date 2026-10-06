/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.FeedableInputStream;
import com.standardapplied.helios.core.test.LineSink;
import com.standardapplied.helios.repl.protocol.ProcessTransport;
import com.standardapplied.helios.repl.protocol.RpcMessage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import jdk.jshell.JShell;
import jdk.jshell.execution.LocalExecutionControl;
import jdk.jshell.execution.LocalExecutionControlProvider;
import jdk.jshell.spi.ExecutionControl;
import jdk.jshell.spi.ExecutionControl.EngineTerminationException;
import jdk.jshell.spi.ExecutionControlProvider;
import jdk.jshell.spi.ExecutionEnv;

/**
 * The sandbox bootstrap's read loop, evaluator and host-bridge state standing in for the sandbox
 * subprocess, the state installed as the instance {@link HostBridge} calls into, with the test as
 * its host. The test feeds the lines the host would send and takes the lines the bootstrap writes,
 * one at a time, so it reads a request only once the bootstrap has written it and no stream fails
 * because a thread exited.
 */
final class BootstrapEnvironment implements AutoCloseable {

  private final JShell jshell;
  private final FeedableInputStream fromHost = new FeedableInputStream();
  private final LineSink toHost = new LineSink();
  private final CompletableFuture<Integer> exitStatus = new CompletableFuture<>();
  private final HostBridgeState bridge;
  private final SnippetEvaluator evaluator;
  private final BootstrapRpc rpc;
  private volatile CompletableFuture<Void> nextTimeout = new CompletableFuture<>();
  private Thread readLoop;

  /**
   * An environment whose executes time out by the clock and in which a stopped snippet always ends
   * within the grace it is given.
   */
  BootstrapEnvironment() {
    this(Await.HANG_GUARD.multipliedBy(5), new LocalExecutionControlProvider(), false);
  }

  /**
   * The bootstrap never exits the JVM; it completes {@link #exitStatus()} instead.
   *
   * @param timedOutByTheTest whether an execute times out only when the test calls {@link
   *     #timeOut()}, whatever its {@code timeoutMs}
   */
  private BootstrapEnvironment(
      Duration stopGrace, ExecutionControlProvider engine, boolean timedOutByTheTest) {
    jshell = newJShell(engine);
    SnippetEvaluator.ExecutionTimer timer =
        timedOutByTheTest ? this::endsBeforeTheTestTimesItOut : Thread::join;
    bridge = new HostBridgeState(new PrintStream(toHost, true, StandardCharsets.UTF_8));
    evaluator = new SnippetEvaluator(jshell, bridge, timer, stopGrace);
    rpc =
        new BootstrapRpc(
            new BufferedReader(new InputStreamReader(fromHost, StandardCharsets.UTF_8)),
            bridge,
            evaluator,
            exitStatus::complete);
    HostBridgeState.setInstance(bridge);
  }

  /**
   * An environment whose executes time out when the test calls {@link #timeOut()}, so a test can
   * let a snippet reach the point it blocks at first, and whose bootstrap gives a stopped snippet
   * {@code stopGrace} to end.
   */
  static BootstrapEnvironment timedOutByTheTest(Duration stopGrace) {
    return new BootstrapEnvironment(stopGrace, new LocalExecutionControlProvider(), true);
  }

  /**
   * Like {@link #timedOutByTheTest}, with a JShell that throws {@code failure} from every {@link
   * JShell#stop()}, as a defect in the execution engine would.
   */
  static BootstrapEnvironment failingStop(Duration stopGrace, RuntimeException failure) {
    return new BootstrapEnvironment(
        stopGrace,
        new LocalExecutionControlProvider() {
          @Override
          public ExecutionControl createExecutionControl(
              ExecutionEnv env, Map<String, String> parameters) {
            return new LocalExecutionControl() {
              @Override
              public void stop() {
                throw failure;
              }
            };
          }
        },
        true);
  }

  /**
   * An environment whose executes time out by the clock and whose engine has terminated by the time
   * a variable's value is read, so JShell fails every read with {@code message}.
   */
  static BootstrapEnvironment failingVarValue(String message) {
    return new BootstrapEnvironment(
        Await.HANG_GUARD.multipliedBy(5),
        new LocalExecutionControlProvider() {
          @Override
          public ExecutionControl createExecutionControl(
              ExecutionEnv env, Map<String, String> parameters) {
            return new LocalExecutionControl() {
              @Override
              public String varValue(String className, String varName)
                  throws EngineTerminationException {
                throw new EngineTerminationException(message);
              }
            };
          }
        },
        false);
  }

  /** An environment whose bootstrap is already reading what the test feeds. */
  static BootstrapEnvironment reading() {
    var environment = new BootstrapEnvironment();
    environment.startReadLoop();
    return environment;
  }

  HostBridgeState bridge() {
    return bridge;
  }

  SnippetEvaluator evaluator() {
    return evaluator;
  }

  BootstrapRpc rpc() {
    return rpc;
  }

  /**
   * Times out the execute waiting for its eval thread, or else the next one to wait, in an
   * environment from {@link #timedOutByTheTest} or {@link #failingStop}.
   */
  void timeOut() {
    nextTimeout.complete(null);
  }

  /** The status the bootstrap exited the sandbox with; incomplete while it has not. */
  CompletableFuture<Integer> exitStatus() {
    return exitStatus;
  }

  void startReadLoop() {
    readLoop = Thread.ofVirtual().name("test-readloop").start(rpc::readLoop);
  }

  /** Runs {@code sandboxCode} on its own thread, as a snippet calling into the host would run. */
  <T> CompletableFuture<T> inSandbox(Supplier<T> sandboxCode) {
    return CompletableFuture.supplyAsync(sandboxCode, Thread.ofVirtual()::start);
  }

  void feed(RpcMessage message) {
    try {
      feedLine(ProcessTransport.serializeMessage(message));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  void feedLine(String line) {
    fromHost.feed(line + "\n");
  }

  /** Ends the host's input: the read loop, running or started later, sees end-of-stream. */
  void endInput() {
    fromHost.end();
  }

  void awaitReadLoopEnd() {
    Await.termination("the bootstrap's read loop", readLoop);
  }

  String nextLine() {
    return toHost.nextLine();
  }

  /** The next message the bootstrap writes to the host, which must carry the RPC prefix. */
  RpcMessage nextMessage() {
    var line = nextLine();
    assertTrue(line.startsWith(ProcessTransport.RPC_PREFIX), line);
    try {
      return ProcessTransport.deserializeMessage(
          line.substring(ProcessTransport.RPC_PREFIX.length()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  RpcMessage.Request nextRequest() {
    return assertInstanceOf(RpcMessage.Request.class, nextMessage());
  }

  @Override
  public void close() {
    endInput();
    if (readLoop != null) {
      awaitReadLoopEnd();
    }
    HostBridgeState.setInstance(null);
    jshell.close();
  }

  private boolean endsBeforeTheTestTimesItOut(Thread evalThread, Duration ignoredTimeout) {
    var timeout = nextTimeout;
    var ended = CompletableFuture.runAsync(() -> join(evalThread), Thread.ofVirtual()::start);
    CompletableFuture.anyOf(ended, timeout).join();
    if (!timeout.isDone()) {
      return true;
    }
    nextTimeout = new CompletableFuture<>();
    return false;
  }

  private static void join(Thread thread) {
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * In the test JVM the module's classes are not on JShell's own classpath, so snippets that call
   * {@link HostBridge} need the build output added to it.
   *
   * <p>The source analysis is taken last, on the test's thread, as the bootstrap's {@code main}
   * takes it installing the prelude after its imports: the first analysis in a JVM starts JShell's
   * indexing thread, which never ends. Started by an execute's eval thread it would join that
   * execute's thread group, and a timed-out snippet would be given up as unstoppable once the stop
   * grace expired.
   */
  private static JShell newJShell(ExecutionControlProvider engine) {
    var jshell = JShell.builder().executionEngine(engine, Map.of()).build();
    var targetClasses = Path.of("target", "classes").toAbsolutePath();
    if (Files.isDirectory(targetClasses)) {
      jshell.addToClasspath(targetClasses.toString());
    }
    jshell.eval("import static com.standardapplied.helios.repl.sandbox.HostBridge.*;");
    jshell.eval("import com.standardapplied.helios.repl.sandbox.HostBridge;");
    jshell.sourceCodeAnalysis();
    return jshell;
  }
}
