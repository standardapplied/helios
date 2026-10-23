/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.process;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A child process and its descendants, signalled together. The descendants are captured once,
 * before the first signal, so one that the child's death re-parents is still signalled; each signal
 * also reaches any descendant visible at that moment, and the child last.
 */
final class ProcessTree {

  private final Process process;
  private List<ProcessHandle> captured;

  ProcessTree(Process process) {
    this.process = process;
  }

  /** Ask every member to terminate. Safe to call from any thread, repeatedly. */
  void kill() {
    members().forEach(ProcessHandle::destroy);
  }

  /**
   * Kill the tree, give it {@code grace} to end, then kill whatever of it is still alive forcibly
   * and give that another {@code grace} to end. An interrupt does not cut either grace short; it is
   * kept for the caller.
   */
  void killAndReap(Duration grace) {
    kill();
    if (!endWithin(members(), grace)) {
      var remaining = members();
      remaining.forEach(ProcessHandle::destroyForcibly);
      endWithin(remaining, grace);
    }
  }

  private synchronized List<ProcessHandle> members() {
    if (captured == null) {
      captured = process.descendants().toList();
    }
    return Stream.of(captured.stream(), process.descendants(), Stream.of(process.toHandle()))
        .flatMap(handles -> handles)
        .distinct()
        .toList();
  }

  /**
   * Waits on this thread's own clock, not on a timer shared with the rest of the JVM, which a
   * blocking callback elsewhere can hold up.
   */
  private static boolean endWithin(List<ProcessHandle> handles, Duration grace) {
    var ended = new CountDownLatch(handles.size());
    handles.forEach(handle -> handle.onExit().thenRun(ended::countDown));
    var deadline = System.nanoTime() + grace.toNanos();
    var interrupted = false;
    try {
      while (true) {
        try {
          return ended.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
