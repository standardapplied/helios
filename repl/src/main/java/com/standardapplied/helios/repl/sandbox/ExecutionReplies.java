/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@link ExecutionResult} the host reports for each way an execute can end: the bootstrap's
 * reply, which is a map of its output, exit code, timeout flag and bindings or else a bare value;
 * an RPC failure; a sandbox that did not answer in time; or a sandbox that is no longer alive. What
 * the subprocess printed outside the reply comes first in the result's stdout.
 */
final class ExecutionReplies {

  private ExecutionReplies() {}

  /** The result of a reply to an execute of {@code executedCode}. */
  static ExecutionResult toExecutionResult(
      String executedCode, Object reply, String capturedStdout) {
    if (reply instanceof Map<?, ?> map) {
      return fromMap(executedCode, map, capturedStdout);
    }
    return fromValue(executedCode, reply, capturedStdout);
  }

  /** The result of an execute whose call failed with {@code message}. */
  static ExecutionResult rpcFailure(String executedCode, String capturedStdout, String message) {
    return ExecutionResult.newBuilder()
        .withExecutedCode(executedCode)
        .withStdout(capturedStdout)
        .withStderr(message)
        .withExitCode(1)
        .build();
  }

  /** The result of an execute the sandbox did not answer within {@code wait}, closing it. */
  static ExecutionResult unanswered(String executedCode, String capturedStdout, Duration wait) {
    return ExecutionResult.newBuilder()
        .withExecutedCode(executedCode)
        .withStdout(capturedStdout)
        .withStderr("Sandbox did not answer the execute within " + wait + "; the sandbox is closed")
        .withExitCode(1)
        .withTimedOut(true)
        .build();
  }

  /** The result of an execute sent to a sandbox that is closed or whose subprocess has exited. */
  static ExecutionResult notAlive(boolean closed, Process process) {
    if (closed) {
      return ExecutionResult.failure("Sandbox process is not alive: the sandbox is closed");
    }
    var exitCode = process.exitValue();
    var reason = "Sandbox process is not alive: it exited with code " + exitCode;
    if (exitCode == JvmSandboxBootstrap.UNSTOPPABLE_SNIPPET_EXIT_CODE) {
      reason += "; the sandbox exited because a timed-out snippet could not be stopped";
    }
    return ExecutionResult.failure(reason);
  }

  private static ExecutionResult fromMap(
      String executedCode, Map<?, ?> reply, String capturedStdout) {
    var stdout = reply.get("stdout") instanceof String s ? s : "";
    var stderr = reply.get("stderr") instanceof String s ? s : "";
    var exitCode = reply.get("exitCode") instanceof Number n ? n.intValue() : 0;
    var timedOut = reply.get("timedOut") instanceof Boolean b && b;
    return new ExecutionResult(
        executedCode,
        precededBy(capturedStdout, stdout),
        stderr,
        exitCode,
        timedOut,
        bindings(reply.get("bindings")),
        Duration.ZERO);
  }

  private static ExecutionResult fromValue(
      String executedCode, Object value, String capturedStdout) {
    var stdout = capturedStdout.isEmpty() ? String.valueOf(value) : capturedStdout;
    return new ExecutionResult(executedCode, stdout, "", 0, false, Map.of(), Duration.ZERO);
  }

  private static Map<String, String> bindings(Object raw) {
    if (!(raw instanceof Map<?, ?> entries)) {
      return Map.of();
    }
    var bindings = new LinkedHashMap<String, String>();
    for (var entry : entries.entrySet()) {
      if (entry.getKey() instanceof String key) {
        bindings.put(key, String.valueOf(entry.getValue()));
      }
    }
    return Collections.unmodifiableMap(bindings);
  }

  private static String precededBy(String capturedStdout, String stdout) {
    if (capturedStdout.isEmpty()) {
      return stdout;
    }
    return stdout.isEmpty() ? capturedStdout : capturedStdout + "\n" + stdout;
  }
}
