/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import java.io.ByteArrayOutputStream;
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
 * and stderr by swapping {@code System.out} and {@code System.err}, runs it on a platform thread in
 * a thread group of its own so a timeout can find every thread the snippet started, and answers
 * with its output, exit code and, on request, a snapshot of its bindings.
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
    var maxBindingValueChars =
        params.get("maxBindingValueChars") instanceof Number bn ? bn.intValue() : 200;
    var maxBindingSnapshotChars =
        params.get("maxBindingSnapshotChars") instanceof Number tn ? tn.intValue() : 16 * 1024;
    var captureBindings = params.get("captureBindings") instanceof Boolean cb ? cb : Boolean.TRUE;

    var stdoutCapture = new ByteArrayOutputStream();
    var stderrCapture = new ByteArrayOutputStream();
    var captureOut = new PrintStream(stdoutCapture, true, StandardCharsets.UTF_8);
    var captureErr = new PrintStream(stderrCapture, true, StandardCharsets.UTF_8);
    var timeoutCapture = new ByteArrayOutputStream();
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
    result.put("stdout", stdoutCapture.toString(StandardCharsets.UTF_8));
    result.put(
        "stderr",
        stderrCapture.toString(StandardCharsets.UTF_8)
            + timeoutCapture.toString(StandardCharsets.UTF_8));
    result.put("exitCode", exitCode.get());
    result.put("timedOut", timedOut.get());
    if (Boolean.TRUE.equals(captureBindings) && unstoppableExecution == null) {
      result.put("bindings", collectBindings(maxBindingValueChars, maxBindingSnapshotChars));
    }
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

  /**
   * Snapshot every user-declared {@code var} in JShell, filtered to exclude harness-internal {@code
   * __}-prefixed names, with each value's {@code toString} repr capped per-value and the total
   * snapshot capped to a budget. The repr is whatever JShell's {@code varValue} returns (which is
   * itself the runtime {@code toString}); a custom {@code toString} that throws gets its message
   * folded into the value as {@code "<error: ...>"} rather than aborting the snapshot.
   */
  Map<String, String> collectBindings(int maxValueChars, int maxSnapshotChars) {
    var snapshot = new LinkedHashMap<String, String>();
    var totalChars = 0;
    var snippets = jshell.variables().toList();
    for (var snippet : snippets) {
      var name = snippet.name();
      if (name.startsWith("__")) {
        continue;
      }
      String repr;
      try {
        repr = jshell.varValue(snippet);
      } catch (Throwable e) {
        // Catch Throwable here (not just Exception): a malicious toString() can throw
        // StackOverflowError, OutOfMemoryError, or AssertionError. Aborting the snapshot would
        // kill the response with no bindings map, and propagate out of doExecute into the virtual
        // thread's uncaught handler. The "<error: …>" stub is a recoverable substitute regardless
        // of the failure mode.
        repr = "<error: " + e.getClass().getSimpleName() + ": " + e.getMessage() + ">";
      }
      if (repr == null) {
        repr = "null";
      }
      if (maxValueChars > 0 && repr.length() > maxValueChars) {
        repr = repr.substring(0, maxValueChars) + "... (len=" + repr.length() + ")";
      }
      if (maxSnapshotChars > 0 && totalChars + name.length() + repr.length() > maxSnapshotChars) {
        snapshot.put(
            "__truncated__",
            "(snapshot exceeded " + maxSnapshotChars + " chars; remaining vars dropped)");
        break;
      }
      totalChars += name.length() + repr.length();
      snapshot.put(name, repr);
    }
    return snapshot;
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
