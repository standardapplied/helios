/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.execution;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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

  private final Set<Process> inflight = ConcurrentHashMap.newKeySet();
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

  void track(Process process) {
    inflight.add(process);
    // close() marks the set closed and then scans it once. A process added after that scan is
    // one close() never saw, so the call that tracks it reaps it here.
    if (closed.get()) {
      reap();
    }
  }

  void untrack(Process process) {
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
    for (var p : List.copyOf(inflight)) {
      try {
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
          p.descendants().forEach(ProcessHandle::destroyForcibly);
          p.destroyForcibly();
          p.waitFor(1, TimeUnit.SECONDS);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (RuntimeException e) {
        LOGGER.log(Level.WARNING, "failed to reap subprocess on provider close", e);
      } finally {
        inflight.remove(p);
      }
    }
  }
}
