/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cooperative cancellation signal.
 *
 * <p>One writer (typically the caller that creates the token), many readers (agent loops, tool
 * implementations, model stream subscribers, durable-resume scanners). State is set once;
 * subsequent {@link #cancel(String)} calls are no-ops and the first reason is preserved.
 *
 * <p>Cancellation is cooperative: code performing long-running work is responsible for polling
 * {@link #isCancelled()} or calling {@link #throwIfCancelled()} at safe points. The token itself
 * does not interrupt OS threads, close I/O streams, or unsubscribe {@code Flow.Subscription}s —
 * those side effects are wired by the consumer.
 *
 * <h2>Linearization</h2>
 *
 * A single private lock guards the cancellation reason and the set of pending registrations. {@link
 * #onCancel(Runnable)}, {@link #cancel(String)} and {@link Registration#remove()} each update that
 * state in one critical section, so every call takes effect at a well-defined point in a total
 * order:
 *
 * <ul>
 *   <li>A registration whose {@code onCancel} is ordered before the winning {@code cancel} fires
 *       exactly once, on the cancelling thread, unless its {@code remove()} is ordered in between.
 *   <li>A registration ordered after the winning {@code cancel} fires exactly once, immediately, on
 *       the registering thread.
 *   <li>A {@code remove()} ordered before the winning {@code cancel} guarantees the callback never
 *       runs. A {@code remove()} ordered after it is a no-op: the callback is already claimed by
 *       the cancelling thread and may be running or complete. {@code remove()} never blocks on it.
 * </ul>
 *
 * <p>Callbacks always run outside the lock, so a callback may register, remove, or cancel
 * reentrantly without deadlocking. A callback throwing a {@link RuntimeException} is logged at
 * {@code WARNING} and does not stop later callbacks. A callback throwing an {@link Error} does not
 * stop later callbacks either, but once every claimed callback has run the first {@code Error} is
 * rethrown with any later ones attached as suppressed — the same "Errors escape, cleanup still
 * runs" rule the session loop applies to host failures.
 *
 * <p>Fired and removed registrations are dropped from the token immediately, so a long-lived token
 * retains only the callbacks that are still armed.
 */
public final class CancellationToken {

  private static final Logger LOGGER = Logger.getLogger(CancellationToken.class.getName());

  private final Object lock = new Object();
  private final LinkedHashSet<CallbackRegistration> registrations = new LinkedHashSet<>();
  private volatile String reason;

  /**
   * Whether {@link #cancel(String)} has been called at least once.
   *
   * @return {@code true} if cancelled
   */
  public boolean isCancelled() {
    return reason != null;
  }

  /**
   * The reason recorded by the first successful {@link #cancel(String)} call.
   *
   * @return the cancellation reason, or {@link Optional#empty()} if not cancelled
   */
  public Optional<String> reason() {
    return Optional.ofNullable(reason);
  }

  /**
   * Signal cancellation. The first call with a non-null, non-blank reason wins; subsequent calls
   * are no-ops and the first reason is preserved. The return value lets callers distinguish "I was
   * the cause" from "someone else cancelled first" — useful for audit attribution.
   *
   * <p>The winning call claims every pending registration atomically with the state transition and
   * then runs the claimed callbacks on the calling thread, in registration order, outside the lock.
   * The method returns only after all claimed callbacks have run.
   *
   * @param reason a human-readable reason for the cancellation
   * @return {@code true} if this call transitioned the token to cancelled; {@code false} if it was
   *     already cancelled
   * @throws NullPointerException if {@code reason} is null
   * @throws IllegalArgumentException if {@code reason} is blank
   * @throws Error the first {@code Error} thrown by a claimed callback, after every other claimed
   *     callback has run
   */
  public boolean cancel(String reason) {
    Objects.requireNonNull(reason, "reason must not be null");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    List<CallbackRegistration> claimed;
    synchronized (lock) {
      if (this.reason != null) {
        return false;
      }
      this.reason = reason;
      claimed = List.copyOf(registrations);
      registrations.clear();
    }
    fire(claimed);
    return true;
  }

  /**
   * Register a callback that runs synchronously when the token transitions to cancelled. If the
   * token is already cancelled, the callback runs immediately on the calling thread and the
   * returned handle is {@link Registration#NOOP}.
   *
   * <p>Callbacks are not deduplicated — register the same callback twice and it fires twice.
   *
   * <p>The returned {@link Registration} lets callers deregister the callback once the work it
   * guards has completed — important for long-lived tokens (per-session) against which many short-
   * lived callers register (per-tool-call, per-execute). Calling {@link Registration#remove()}
   * after the token has claimed the callback is a safe no-op; see the class-level linearization
   * contract.
   *
   * @param callback the work to run on cancellation; non-null
   * @return a handle for removing this callback before cancellation claims it
   * @throws NullPointerException if {@code callback} is null
   */
  public Registration onCancel(Runnable callback) {
    Objects.requireNonNull(callback, "callback must not be null");
    var registration = new CallbackRegistration(callback);
    synchronized (lock) {
      if (reason == null) {
        registrations.add(registration);
        return registration;
      }
    }
    fire(List.of(registration));
    return Registration.NOOP;
  }

  private void fire(List<CallbackRegistration> claimed) {
    Error hostError = null;
    for (var registration : claimed) {
      try {
        registration.callback.run();
      } catch (RuntimeException ex) {
        LOGGER.log(Level.WARNING, "cancellation callback threw", ex);
      } catch (Error err) {
        if (hostError == null) {
          hostError = err;
        } else {
          hostError.addSuppressed(err);
        }
      }
    }
    if (hostError != null) {
      throw hostError;
    }
  }

  /**
   * Visible-for-testing accessor exposing the count of currently-armed callbacks. Used to verify
   * that per-call sites (tool dispatch, question gateway) correctly invoke {@link
   * Registration#remove()} when their guarded work finishes, so callbacks do not accumulate on the
   * long-lived per-session token.
   *
   * @return the number of {@link #onCancel(Runnable)} registrations that have not yet been claimed
   *     by cancellation or removed
   */
  public int activeCallbackCountForTests() {
    synchronized (lock) {
      return registrations.size();
    }
  }

  /**
   * Handle for a callback registered via {@link CancellationToken#onCancel(Runnable)}. Calling
   * {@link #remove()} detaches the callback so it will not fire on subsequent token cancellation.
   *
   * <p>Idempotent: calling {@code remove()} more than once, or after cancellation has claimed the
   * callback, is a safe no-op. It never blocks on a callback that is already running.
   */
  public sealed interface Registration permits CallbackRegistration, NoopRegistration {

    /** A pre-fired or never-registered handle — {@link #remove()} is a no-op. */
    Registration NOOP = new NoopRegistration();

    /** Detach this registration. Safe to call multiple times and after firing. */
    void remove();
  }

  private final class CallbackRegistration implements Registration {
    private final Runnable callback;

    CallbackRegistration(Runnable callback) {
      this.callback = callback;
    }

    @Override
    public void remove() {
      synchronized (lock) {
        registrations.remove(this);
      }
    }
  }

  private static final class NoopRegistration implements Registration {
    @Override
    public void remove() {
      // No callback to detach.
    }
  }

  /**
   * Throw if this token has been cancelled. Tools and other cooperative cancellation participants
   * should call this at safe points in their work.
   *
   * @throws CancellationException if cancelled; the exception message is the cancellation reason
   */
  public void throwIfCancelled() {
    var r = reason;
    if (r != null) {
      throw new CancellationException(r);
    }
  }
}
