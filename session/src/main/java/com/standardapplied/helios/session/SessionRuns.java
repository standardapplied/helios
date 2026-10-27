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
 * was in progress tears the session down once that run ended, and a host {@code Error} that fails
 * an idle session tears it down on a thread of its own.
 */
final class SessionRuns implements RunBoundary {

  private final SessionState session;
  private final SteeringQueue queue;
  private final SessionLimits limits;
  private final Consumer<Optional<Error>> teardown;

  /** Package-private for tests that hold it to order a run's end against a host {@link Error}. */
  final Object lock = new Object();

  private AgentLoop loop;
  private Run active;
  private CompletableFuture<ResultMessage> next;
  private boolean ending;
  private boolean closed;
  private Error failure;

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
        throw new IllegalStateException("session failed: " + failure.getClass().getName());
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

  /** Cancel the run in progress, if any, with the reason {@code "interrupted"}. */
  void interrupt() {
    activeRun().ifPresent(Run::interrupt);
  }

  /**
   * Fail the session with the first host {@link Error} recorded: no {@code send} is taken from now
   * on, and the run in progress fails with {@code error} once it ended, its thread tearing the
   * session down. An idle session, not already closing, is torn down on a thread of its own, since
   * the thread that recorded {@code error} may be one the teardown waits for.
   */
  void failed(Error error) {
    boolean idle;
    synchronized (lock) {
      idle = active == null && !closed;
      failure = error;
      closed = true;
    }
    if (idle) {
      Thread.ofVirtual()
          .name("helios-failed-session-" + session.sessionId())
          .start(() -> teardown.accept(Optional.empty()));
    }
  }

  /**
   * Start no run from now on. The thread of the run in progress, if any, tears the session down
   * once that run ended, and cancels the future of a next run that was waiting for it.
   *
   * @return the run in progress, if any
   */
  Optional<Run> close() {
    synchronized (lock) {
      closed = true;
      return Optional.ofNullable(active);
    }
  }

  Optional<Run> activeRun() {
    synchronized (lock) {
      return Optional.ofNullable(active);
    }
  }

  /** Start a run whose future is {@code result}; called under the lock. */
  private CompletableFuture<ResultMessage> start(CompletableFuture<ResultMessage> result) {
    var run = new Run(session, result, limits.maxWallClock(), this::execute);
    active = run;
    ending = false;
    run.start();
    return result;
  }

  /**
   * Drive {@code run} to its end, leave it, so a completion callback of its future that sends finds
   * no run in progress, tear the session down once it closed or failed, then settle the futures, so
   * none of their callbacks holds up the teardown; the future of a next run the session's end
   * prevented is cancelled, or fails, on a thread of its own, so a callback of either future may
   * wait for the other. {@link AgentLoop#run} turns every {@link Exception} into a terminal, so
   * only an {@link Error} escapes it, which goes to the session's ending; the run fails with the
   * {@code Error} that failed the session, and this thread ends by throwing it to its
   * uncaught-exception handler once the session is torn down. A {@link RuntimeException} that
   * escapes anyway fails the run as itself, so its future never stays pending.
   */
  private void execute(Run run) {
    run.begin();
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
    var left = leave();
    var error = left.error();
    if (left.closed()) {
      teardown.accept(error);
    }
    left.prevented()
        .ifPresent(
            prevented ->
                Thread.ofVirtual()
                    .name("helios-prevented-run-" + session.sessionId())
                    .start(
                        () ->
                            error.ifPresentOrElse(
                                prevented::completeExceptionally, () -> prevented.cancel(false))));
    if (error.isPresent()) {
      run.result().completeExceptionally(error.get());
      throw error.get();
    }
    if (crash != null) {
      run.result().completeExceptionally(crash);
    } else {
      run.result().complete(terminal);
    }
  }

  /**
   * Leave the ended run: a host {@link Error} the session's ending recorded fails the session here
   * too, since its {@link #failed} listener may not have run yet, and it is read under the lock
   * {@code failed} takes, so an {@code Error} recorded later finds the session idle; a run that
   * ended without deciding it, such as one a host {@code Error} ended, decides it now, so no
   * message stays queued for it; then the next run starts if a message is waiting for one and the
   * session is open, or else that run's future is returned, to be cancelled or to fail with the
   * {@code Error} that failed the session.
   */
  private Left leave() {
    synchronized (lock) {
      var recorded = session.ending().first();
      failure = recorded.orElse(failure);
      closed |= recorded.isPresent();
      if (!ending) {
        queue.drain();
      }
      active = null;
      ending = false;
      var prevented = next;
      next = null;
      if (prevented != null && !closed) {
        start(prevented);
        return new Left(Optional.empty(), false, Optional.empty());
      }
      return new Left(recorded, closed, Optional.ofNullable(prevented));
    }
  }

  /**
   * How a run was left: the {@link Error} that failed the session, if any, whether the session is
   * closed, so the run's thread tears it down, and the future of the next run its end keeps from
   * starting, if any.
   */
  private record Left(
      Optional<Error> error,
      boolean closed,
      Optional<CompletableFuture<ResultMessage>> prevented) {}
}
