/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.execution;

import com.standardapplied.helios.core.process.BoundedProcess;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The child processes a {@link LocalProcessExecutionProvider} is running, and their reaping.
 * Closing destroys every tracked process with its descendants, and so does a process tracked after
 * the close; an optional JVM shutdown hook reaps whatever is still running if the host exits
 * without closing.
 */
final class InflightProcesses implements AutoCloseable {

  private static final Logger LOGGER =
      Logger.getLogger(LocalProcessExecutionProvider.class.getName());

  private static final java.lang.Runtime JVM = java.lang.Runtime.getRuntime();

  // Not a ConcurrentHashMap key set: its size() lags a removal by another thread, and a call's
  // untrack races the reap, so size() could still count a process close() had already reaped.
  private final Set<BoundedProcess> inflight = Collections.synchronizedSet(new HashSet<>());
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Thread shutdownHook = new Thread(this::reap, "helios-exec-shutdown");
  private final boolean shutdownHookRegistered;

  InflightProcesses(boolean registerShutdownHook) {
    this.shutdownHookRegistered = registerShutdownHook;
    if (registerShutdownHook) {
      JVM.addShutdownHook(shutdownHook);
    }
  }

  boolean isClosed() {
    return closed.get();
  }

  int size() {
    return inflight.size();
  }

  void track(BoundedProcess process) {
    inflight.add(process);
    // close() marks the set closed and then scans it once. A process added after that scan is
    // one close() never saw, so the call that tracks it reaps it here.
    if (closed.get()) {
      reap();
    }
  }

  void untrack(BoundedProcess process) {
    inflight.remove(process);
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    reap();
    if (shutdownHookRegistered) {
      try {
        JVM.removeShutdownHook(shutdownHook);
      } catch (IllegalStateException ignored) {
        // JVM is already shutting down — the hook is running or has run.
      }
    }
  }

  private void reap() {
    for (var process : List.copyOf(inflight)) {
      try {
        process.kill();
      } catch (RuntimeException e) {
        LOGGER.log(Level.WARNING, "failed to reap subprocess on provider close", e);
      } finally {
        inflight.remove(process);
      }
    }
  }
}
