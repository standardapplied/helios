/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import com.standardapplied.helios.repl.protocol.CapturedOutput;
import java.time.Duration;
import java.util.Map;

/**
 * The {@link ExecutionResult} the host reports for each way an execute can end: the bootstrap's
 * reply, which is a map of its output, exit code and timeout flag or else a bare value; an RPC
 * failure; a sandbox that did not answer in time; or a sandbox that is no longer alive. What the
 * subprocess printed outside the reply comes first in the result's stdout, and the two are cut as
 * one output.
 */
final class ExecutionReplies {

  private ExecutionReplies() {}

  /** The result of a reply to an execute. */
  static ExecutionResult toExecutionResult(Object reply, CapturedOutput.Cut capturedStdout) {
    if (reply instanceof Map<?, ?> map) {
      return fromMap(map, capturedStdout);
    }
    return fromValue(reply, capturedStdout);
  }

  /** The result of an execute whose call failed with {@code message}. */
  static ExecutionResult rpcFailure(String capturedStdout, String message) {
    return ExecutionResult.newBuilder()
        .withStdout(capturedStdout)
        .withStderr(message)
        .withExitCode(1)
        .build();
  }

  /** The result of an execute the sandbox did not answer within {@code wait}, once it is closed. */
  static ExecutionResult unanswered(String capturedStdout, Duration wait) {
    return ExecutionResult.newBuilder()
        .withStdout(capturedStdout)
        .withStderr("Sandbox did not answer the execute within " + wait + "; the sandbox is closed")
        .withExitCode(1)
        .withTimedOut(true)
        .build();
  }

  /**
   * The result of an execute sent to a sandbox that is closed, whose subprocess has exited, or
   * whose RPC channel ended with the subprocess still running.
   */
  static ExecutionResult notAlive(boolean closed, Process process) {
    if (closed) {
      return ExecutionResult.failure("Sandbox process is not alive: the sandbox is closed");
    }
    if (process.isAlive()) {
      return ExecutionResult.failure("Sandbox is not alive: its RPC channel closed");
    }
    var exitCode = process.exitValue();
    var reason = "Sandbox process is not alive: it exited with code " + exitCode;
    if (exitCode == JvmSandboxBootstrap.UNSTOPPABLE_SNIPPET_EXIT_CODE) {
      reason += "; the sandbox exited because a timed-out snippet could not be stopped";
    }
    return ExecutionResult.failure(reason);
  }

  private static ExecutionResult fromMap(Map<?, ?> reply, CapturedOutput.Cut capturedStdout) {
    var stdout = reply.get("stdout") instanceof String s ? s : "";
    var stdoutDiscarded = reply.get("stdoutDiscarded") instanceof Number n ? n.longValue() : 0;
    var stderr = reply.get("stderr") instanceof String s ? s : "";
    var exitCode = reply.get("exitCode") instanceof Number n ? n.intValue() : 0;
    var timedOut = reply.get("timedOut") instanceof Boolean b && b;
    var joined = precededBy(capturedStdout, stdout, stdoutDiscarded);
    return new ExecutionResult(joined, stderr, exitCode, timedOut, Duration.ZERO);
  }

  private static ExecutionResult fromValue(Object value, CapturedOutput.Cut capturedStdout) {
    var captured = capturedStdout.text();
    var stdout = captured.isEmpty() ? String.valueOf(value) : captured;
    return new ExecutionResult(stdout, "", 0, false, Duration.ZERO);
  }

  private static String precededBy(
      CapturedOutput.Cut capturedStdout, String stdout, long stdoutDiscarded) {
    var joined = new CapturedOutput();
    joined.append(capturedStdout);
    if (!stdout.isEmpty()) {
      joined.appendLine(stdout);
    }
    joined.discard(stdoutDiscarded);
    return joined.drain();
  }
}
