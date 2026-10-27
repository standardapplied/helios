/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * One piece of a session's work run on a virtual thread of its own, so that the thread waiting for
 * it can stop waiting at a deadline: what the task returns or throws settles {@link #outcome()},
 * and what it throws is first handed to the caller's {@code failures}, the session's {@code
 * SessionEnding.failWith} for the session's own work, which records an {@link Error} it is or
 * carries as the cause of a joined future's exception.
 *
 * @param <T> what the task returns
 */
final class BoundedTask<T> {

  private final Thread thread;
  private final CompletableFuture<T> outcome;

  private BoundedTask(Thread thread, CompletableFuture<T> outcome) {
    this.thread = thread;
    this.outcome = outcome;
  }

  /** Start {@code task} on a new virtual thread named {@code threadName}. */
  static <T> BoundedTask<T> start(
      String threadName, Callable<T> task, Consumer<Throwable> failures) {
    var outcome = new CompletableFuture<T>();
    var thread =
        Thread.ofVirtual()
            .name(threadName)
            .start(
                () -> {
                  try {
                    outcome.complete(task.call());
                  } catch (Throwable t) {
                    failures.accept(t);
                    outcome.completeExceptionally(t);
                  }
                });
    return new BoundedTask<>(thread, outcome);
  }

  /**
   * The {@link System#nanoTime()} instant {@code bound} from now; a bound past {@code
   * Long.MAX_VALUE} nanoseconds counts as that many.
   */
  static long deadlineAfter(Duration bound) {
    return System.nanoTime() + TimeUnit.NANOSECONDS.convert(bound);
  }

  /**
   * Wait until {@code step} settled or the {@link System#nanoTime()} instant {@code deadlineNanos}
   * passes; an interrupt of the waiting thread ends the wait, its interrupt status kept.
   *
   * @return {@code true} if {@code step} settled, with a value or a failure, by the deadline
   */
  static boolean settlesBy(Future<?> step, long deadlineNanos) {
    try {
      step.get(deadlineNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
      return true;
    } catch (ExecutionException | CancellationException e) {
      return true;
    } catch (TimeoutException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** What the task returned or threw, once it has. */
  CompletableFuture<T> outcome() {
    return outcome;
  }

  /** As {@link #settlesBy(Future, long)} for this task's {@link #outcome()}. */
  boolean settlesBy(long deadlineNanos) {
    return settlesBy(outcome, deadlineNanos);
  }

  /** Interrupt the task's thread, asking a task the session stopped waiting for to stop. */
  void interrupt() {
    thread.interrupt();
  }
}
