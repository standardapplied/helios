/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * A scheduler whose deadlines the test brings due: it runs the latest one on its own thread, as if
 * its delay had passed. A deadline still comes due on its own when its delay passes, so a test
 * gives every deadline it fires by hand a delay beyond the hang guard. A cancelled deadline leaves
 * the queue at once, as on the session's scheduler.
 */
final class ManualScheduler extends ScheduledThreadPoolExecutor {

  private final List<Runnable> scheduled = new CopyOnWriteArrayList<>();
  private final List<RunnableScheduledFuture<?>> deadlines = new CopyOnWriteArrayList<>();

  ManualScheduler() {
    super(1);
    setRemoveOnCancelPolicy(true);
  }

  @Override
  protected <V> RunnableScheduledFuture<V> decorateTask(
      Runnable runnable, RunnableScheduledFuture<V> task) {
    scheduled.add(runnable);
    deadlines.add(task);
    return task;
  }

  void fireLatest() {
    scheduled.getLast().run();
  }

  /** Whether each deadline scheduled so far, in order, has been cancelled. */
  List<Boolean> cancelled() {
    return deadlines.stream().map(RunnableScheduledFuture::isCancelled).toList();
  }
}
