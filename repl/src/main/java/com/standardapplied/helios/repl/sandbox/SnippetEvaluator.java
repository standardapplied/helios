/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;

/**
 * Evaluates the host's snippets in the sandbox's JShell. Each execute captures the snippet's stdout
 * and stderr by swapping {@code System.out} and {@code System.err} for {@link CapturedStream}s,
 * runs it on a platform thread in a thread group of its own so a timeout can find every thread the
 * snippet started, and answers with its output, exit code and timeout flag.
 *
 * <p>Only one execute may run at a time: the standard streams are JVM-global, so concurrent
 * executes would corrupt each other's capture. A {@link Semaphore} rejects the second; the host
 * side ({@link com.standardapplied.helios.repl.protocol.RpcChannel#call RpcChannel.call}) also
 * serializes naturally by blocking until each response arrives.
 */
final class SnippetEvaluator {

  private final JShell jshell;
  private final Semaphore executeLock = new Semaphore(1);
  private final ExecutionTimer timer;
  private final SnippetStopper stopper;
  private volatile Thread unstoppableExecution;

  /** Waits for an execute's eval thread, the one wait that ends an execute by its timeout. */
  @FunctionalInterface
  interface ExecutionTimer {

    /** {@code true} if {@code evalThread} ended within {@code timeout}. */
    boolean awaitEnd(Thread evalThread, Duration timeout) throws InterruptedException;
  }

  /**
   * @param jshell the sandbox's JShell
   * @param timer waits for each execute's eval thread; {@link Thread#join(Duration)} outside tests
   * @param stopGrace how long a timed-out snippet has to end after {@link JShell#stop()} before the
   *     sandbox gives it up as unstoppable
   */
  SnippetEvaluator(JShell jshell, ExecutionTimer timer, Duration stopGrace) {
    this.jshell = jshell;
    this.timer = timer;
    this.stopper = new SnippetStopper(jshell, stopGrace);
  }

  Map<String, Object> handleExecute(Map<String, Object> params) {
    if (!executeLock.tryAcquire()) {
      var error = new LinkedHashMap<String, Object>();
      error.put("stdout", "");
      error.put("stderr", "Concurrent execution rejected — only one execute may run at a time");
      error.put("exitCode", 1);
      return error;
    }
    try {
      return doExecute(params);
    } finally {
      executeLock.release();
    }
  }

  /**
   * Evaluate a registry-derived JShell snippet at boot time. Called by {@code JvmSandbox} via the
   * {@code installPrelude} RPC after the subprocess starts but before the first user execute. Any
   * REJECTED snippet event is collected into the response so the parent can surface the error
   * without having to dig through stderr.
   */
  Map<String, Object> handleInstallPrelude(Map<String, Object> params) {
    var snippet = params.get("snippet") instanceof String s ? s : "";
    if (snippet.isBlank()) {
      return Map.of("success", true);
    }
    var errors = new ArrayList<String>();
    var analysis = jshell.sourceCodeAnalysis();
    var remaining = snippet;
    while (!remaining.isBlank()) {
      var info = analysis.analyzeCompletion(remaining);
      if (!info.completeness().isComplete()) {
        errors.add("Incomplete snippet at: " + info.source());
        break;
      }
      var events = jshell.eval(info.source());
      for (var event : events) {
        if (event.status() == Snippet.Status.REJECTED) {
          jshell.diagnostics(event.snippet()).forEach(d -> errors.add(d.getMessage(null)));
        }
        if (event.exception() != null) {
          errors.add(event.exception().toString());
        }
      }
      remaining = info.remaining();
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("success", errors.isEmpty());
    if (!errors.isEmpty()) {
      result.put("errors", List.copyOf(errors));
    }
    return result;
  }

  /**
   * Whether the execute served on {@code thread} found its snippet unstoppable. A snippet that
   * outlived {@link JShell#stop()} still holds the captured system streams and runs on, so no later
   * execute can be trusted.
   */
  boolean isUnstoppableOn(Thread thread) {
    return unstoppableExecution == thread;
  }

  private Map<String, Object> doExecute(Map<String, Object> params) {
    var code = params.get("code") instanceof String s ? s : "";
    var timeoutMs = params.get("timeoutMs") instanceof Number n ? n.longValue() : 30000L;

    var stdoutCapture = new CapturedStream();
    var stderrCapture = new CapturedStream();
    var captureOut = new PrintStream(stdoutCapture, true, StandardCharsets.UTF_8);
    var captureErr = new PrintStream(stderrCapture, true, StandardCharsets.UTF_8);
    var timeoutCapture = new CapturedStream();
    var timeoutErr = new PrintStream(timeoutCapture, true, StandardCharsets.UTF_8);

    var originalOut = System.out;
    var originalErr = System.err;
    var exitCode = new AtomicInteger(0);
    var timedOut = new AtomicBoolean();

    System.setOut(captureOut);
    System.setErr(captureErr);
    try {
      var executionThreads = new ThreadGroup("jshell-execution");
      var evalThread =
          Thread.ofPlatform()
              .group(executionThreads)
              .daemon()
              .name("jshell-eval")
              .start(
                  () -> {
                    try {
                      if (!evalCode(code, timedOut, captureOut, captureErr)) {
                        exitCode.set(1);
                      }
                    } catch (Exception e) {
                      e.printStackTrace(captureErr);
                      exitCode.set(1);
                    }
                  });

      try {
        if (!timer.awaitEnd(evalThread, Duration.ofMillis(timeoutMs))) {
          stopTimedOutSnippet(executionThreads, timedOut, timeoutErr);
          exitCode.set(1);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        exitCode.set(1);
      }
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }

    var result = new LinkedHashMap<String, Object>();
    result.put("stdout", CapturedStream.text(stdoutCapture));
    result.put("stderr", CapturedStream.text(stderrCapture, timeoutCapture));
    result.put("exitCode", exitCode.get());
    result.put("timedOut", timedOut.get());
    return result;
  }

  /**
   * Ends a snippet that outlived its timeout. Interrupting the eval thread would end the wait and
   * leave the snippet running, so the snippet is stopped through {@link JShell#stop()} instead. The
   * eval thread ending proves nothing about a thread the snippet started after JShell took its one
   * snapshot of the snippet's threads, so every thread of the execution must end within the stop
   * grace. One still running is blocked where neither the interrupt nor JShell's stop check reaches
   * it, and marks the sandbox for exit.
   *
   * @param err a stream of its own: the snippet may hold the monitor of its captured streams
   */
  private void stopTimedOutSnippet(
      ThreadGroup executionThreads, AtomicBoolean timedOut, PrintStream err)
      throws InterruptedException {
    timedOut.set(true);
    err.println("Execution timed out");
    if (!stopper.stopWithinGrace(executionThreads, err)) {
      err.println("The timed-out snippet could not be stopped; the sandbox is shutting down");
      unstoppableExecution = Thread.currentThread();
    }
  }

  private boolean evalCode(String code, AtomicBoolean timedOut, PrintStream out, PrintStream err) {
    var analysis = jshell.sourceCodeAnalysis();
    var remaining = code;
    var success = true;
    while (!remaining.isEmpty() && !timedOut.get()) {
      var info = analysis.analyzeCompletion(remaining);
      if (info.completeness() == SourceCodeAnalysis.Completeness.EMPTY) {
        break;
      }
      for (var event : jshell.eval(info.source())) {
        success &= report(event, out, err);
      }
      remaining = info.remaining();
    }
    return success;
  }

  /**
   * Print what one evaluated snippet produced: its diagnostics and stack trace on {@code err}, the
   * value of an expression on {@code out}.
   *
   * @return {@code false} if the snippet was rejected or threw
   */
  private boolean report(SnippetEvent event, PrintStream out, PrintStream err) {
    var succeeded = true;
    if (event.status() == Snippet.Status.REJECTED) {
      jshell.diagnostics(event.snippet()).forEach(d -> err.println(d.getMessage(null)));
      succeeded = false;
    }
    if (event.exception() != null) {
      event.exception().printStackTrace(err);
      succeeded = false;
    }
    if (event.value() != null
        && (event.snippet().subKind() == Snippet.SubKind.TEMP_VAR_EXPRESSION_SUBKIND
            || event.snippet().kind() == Snippet.Kind.EXPRESSION)) {
      out.println(event.value());
    }
    return succeeded;
  }
}
