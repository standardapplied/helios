/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.session.loop.AgentLoop;
import com.standardapplied.helios.session.loop.RunBoundary;
import com.standardapplied.helios.session.loop.SessionState;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The runs of one session, one at a time. {@link #send} steers the run in progress or starts the
 * next one, and {@link #activeRun} is the run in progress; {@link #end} is where a run's decision
 * to end meets {@code send}, both under one lock, so a message is either part of a run or starts
 * the next, and a message accepted after the run in progress decided to end starts the next run as
 * soon as that one has ended. Each {@link Run} runs on a virtual thread of its own. Once the
 * session is closed, or a host {@link Error} failed it, no run starts; the thread of the run that
 * was in progress hands the session to its teardown after a host {@code Error}, or after a close
 * from a thread the session's runs started.
 */
final class SessionRuns implements RunBoundary {

  private static final InheritableThreadLocal<SessionRuns> WORKER = new InheritableThreadLocal<>();

  private final SessionState session;
  private final SteeringQueue queue;
  private final SessionLimits limits;
  private final Consumer<Optional<Error>> teardown;
  private final Object lock = new Object();
  private AgentLoop loop;
  private Run active;
  private CompletableFuture<ResultMessage> next;
  private boolean ending;
  private boolean closed;
  private boolean closedByWorker;
  private String failure;

  /**
   * @param session the session's own state: its id, history, ending and token
   * @param teardown ends the session after its last run, on that run's thread, given the {@link
   *     Error} that run failed with, if any
   */
  SessionRuns(
      SessionState session,
      SteeringQueue queue,
      SessionLimits limits,
      Consumer<Optional<Error>> teardown) {
    this.session = session;
    this.queue = queue;
    this.limits = limits;
    this.teardown = teardown;
  }

  void bind(AgentLoop loop) {
    this.loop = loop;
  }

  /**
   * Queue {@code message} for the run in progress, or for the next run, which it starts when no run
   * is in progress.
   *
   * @return the future of the run the message is part of; the future of a next run the session's
   *     close prevents is cancelled
   * @throws IllegalStateException if the session is closed or a host {@link Error} failed it
   * @throws SteeringQueueFullException if the queue is full; the message is not queued
   */
  CompletableFuture<ResultMessage> send(UserMessage message) {
    synchronized (lock) {
      if (failure != null) {
        throw new IllegalStateException("session failed: " + failure);
      }
      if (closed) {
        throw new IllegalStateException("session is closed");
      }
      if (!queue.offer(message)) {
        throw new SteeringQueueFullException("steering queue full at capacity " + queue.capacity());
      }
      if (active == null) {
        return start(new CompletableFuture<>());
      }
      if (!ending) {
        return active.result();
      }
      if (next == null) {
        next = new CompletableFuture<>();
      }
      return next;
    }
  }

  @Override
  public boolean end(boolean naturalStop) {
    synchronized (lock) {
      if (naturalStop && queue.size() > 0) {
        return false;
      }
      ending = true;
      queue.drain();
      return true;
    }
  }

  boolean isRunning() {
    return activeRun().isPresent();
  }

  /**
   * Start no run from now on: the future of a next run waiting for the run in progress to end is
   * cancelled. Called on a thread this session's runs started while a run is in progress, the run's
   * own thread tears the session down once the run ended; otherwise the caller does.
   *
   * @return the run in progress, if any
   */
  Optional<Run> close() {
    CompletableFuture<ResultMessage> prevented;
    Optional<Run> running;
    synchronized (lock) {
      closed = true;
      closedByWorker = active != null && onWorkerThread();
      prevented = next;
      next = null;
      running = Optional.ofNullable(active);
    }
    if (prevented != null) {
      prevented.cancel(false);
    }
    return running;
  }

  /** Whether the calling thread is one this session's runs started, directly or not. */
  boolean onWorkerThread() {
    return WORKER.get() == this;
  }

  Optional<Run> activeRun() {
    synchronized (lock) {
      return Optional.ofNullable(active);
    }
  }

  /** Start a run whose future is {@code result}; called under the lock. */
  private CompletableFuture<ResultMessage> start(CompletableFuture<ResultMessage> result) {
    var run = new Run(session, result, limits.maxWallClock());
    active = run;
    ending = false;
    run.startWallClock();
    Thread.ofVirtual().name("helios-agent-run-" + session.sessionId()).start(() -> execute(run));
    return result;
  }

  /**
   * Drive {@code run} to its end and settle its future, then start the next run or, once the
   * session closed or failed, tear the session down. {@link AgentLoop#run} turns every {@link
   * Exception} into a terminal, so only an {@link Error} escapes it, which goes to the session's
   * ending; the run fails with the first {@code Error} recorded, and this thread ends by throwing
   * it to its uncaught-exception handler once the session is torn down. A {@link RuntimeException}
   * that escapes anyway fails the run as itself, so its future never stays pending.
   */
  private void execute(Run run) {
    WORKER.set(this);
    ResultMessage terminal = null;
    RuntimeException crash = null;
    try {
      terminal = loop.run(run.state(), limits, this);
    } catch (RuntimeException e) {
      crash = e;
    } catch (Error e) {
      session.ending().fail(e);
    }
    // An interrupt host code left on this thread is no request to cut the teardown's waits short.
    Thread.interrupted();
    run.unlink();
    var error = session.ending().first();
    settled(error);
    if (error.isPresent()) {
      run.result().completeExceptionally(error.get());
    } else if (crash != null) {
      run.result().completeExceptionally(crash);
    } else {
      run.result().complete(terminal);
    }
    if (afterRun(error)) {
      teardown.accept(error);
    }
    if (error.isPresent()) {
      throw error.get();
    }
  }

  /**
   * Before the run's future completes: a run that ended without deciding it, such as one a host
   * {@link Error} ended, decides it now, so no message stays queued for it, and an {@code Error}
   * fails the session, so no later {@code send} is taken.
   */
  private void settled(Optional<Error> error) {
    synchronized (lock) {
      if (!ending) {
        ending = true;
        queue.drain();
      }
      error.ifPresent(
          e -> {
            failure = e.getClass().getName();
            closed = true;
          });
    }
  }

  /**
   * Leave the ended run: start the next run if a message is waiting for one, or fail that run's
   * future with the {@link Error} that failed the session.
   *
   * @return whether this thread tears the session down: after a host {@link Error}, or a close from
   *     a thread the session's runs started
   */
  private boolean afterRun(Optional<Error> error) {
    CompletableFuture<ResultMessage> prevented;
    boolean ended;
    synchronized (lock) {
      active = null;
      ending = false;
      prevented = next;
      next = null;
      if (prevented != null && !closed) {
        start(prevented);
        return false;
      }
      ended = error.isPresent() || closedByWorker;
    }
    if (prevented != null) {
      prevented.completeExceptionally(error.orElseThrow());
    }
    return ended;
  }
}
