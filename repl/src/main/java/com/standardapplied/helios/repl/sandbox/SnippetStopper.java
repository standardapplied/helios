/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import java.io.PrintStream;
import java.time.Duration;
import jdk.jshell.JShell;

/**
 * Stops a snippet that outlived its timeout through {@link JShell#stop()}, giving every thread of
 * its execution {@code stopGrace} to end.
 */
final class SnippetStopper {

  private static final long STOP_RETRY_NANOS = Duration.ofMillis(20).toNanos();

  private final JShell jshell;
  private final Duration stopGrace;

  SnippetStopper(JShell jshell, Duration stopGrace) {
    this.jshell = jshell;
    this.stopGrace = stopGrace;
  }

  /**
   * {@link JShell#stop()} does nothing while the statement in flight is still compiling or
   * starting, and starting a statement clears the stop JShell may already have requested, so a
   * single stop can be lost. It is repeated until every thread of the execution has ended,
   * rescanning each round because a thread can start another. A stop that fails is reported once
   * and not retried.
   */
  boolean stopWithinGrace(ThreadGroup threads, PrintStream err) throws InterruptedException {
    var deadline = System.nanoTime() + stopGrace.toNanos();
    var stopping = true;
    for (var live = anyLiveThread(threads); live != null; live = anyLiveThread(threads)) {
      var remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return false;
      }
      stopping = stopping && requestStop(err);
      live.join(Duration.ofNanos(Math.min(remaining, STOP_RETRY_NANOS)));
    }
    return true;
  }

  private boolean requestStop(PrintStream err) {
    try {
      jshell.stop();
      return true;
    } catch (RuntimeException jshellStopErr) {
      jshellStopErr.printStackTrace(err);
      return false;
    }
  }

  /** A live thread of the group or any of its subgroups, or {@code null} once none is left. */
  private static Thread anyLiveThread(ThreadGroup threads) {
    var live = new Thread[1];
    return threads.enumerate(live) == 0 ? null : live[0];
  }
}
