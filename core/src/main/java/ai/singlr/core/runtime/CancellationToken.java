/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
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
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. {@code cancel}, {@code isCancelled}, {@code reason}, and {@code throwIfCancelled}
 * may be called from any thread concurrently; the {@code compareAndSet} on the underlying {@link
 * AtomicReference} guarantees that only the first {@code cancel} that wins the race sets the state.
 */
public final class CancellationToken {

  private static final Logger LOGGER = Logger.getLogger(CancellationToken.class.getName());

  private final AtomicReference<String> reason = new AtomicReference<>();
  private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();

  /**
   * Whether {@link #cancel(String)} has been called at least once.
   *
   * @return {@code true} if cancelled
   */
  public boolean isCancelled() {
    return reason.get() != null;
  }

  /**
   * The reason recorded by the first successful {@link #cancel(String)} call.
   *
   * @return the cancellation reason, or {@link Optional#empty()} if not cancelled
   */
  public Optional<String> reason() {
    return Optional.ofNullable(reason.get());
  }

  /**
   * Signal cancellation. The first call with a non-null, non-blank reason wins; subsequent calls
   * are no-ops and the first reason is preserved. The return value lets callers distinguish "I was
   * the cause" from "someone else cancelled first" — useful for audit attribution.
   *
   * @param reason a human-readable reason for the cancellation
   * @return {@code true} if this call transitioned the token to cancelled; {@code false} if it was
   *     already cancelled
   * @throws NullPointerException if {@code reason} is null
   * @throws IllegalArgumentException if {@code reason} is blank
   */
  public boolean cancel(String reason) {
    Objects.requireNonNull(reason, "reason must not be null");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    if (!this.reason.compareAndSet(null, reason)) {
      return false;
    }
    fireCallbacks();
    return true;
  }

  /**
   * Register a callback that runs synchronously when the token transitions to cancelled. If the
   * token is already cancelled, the callback runs immediately on the calling thread.
   *
   * <p>Callbacks fire on the thread that wins the {@link #cancel(String)} race (or the registering
   * thread if already cancelled). Each callback is exception-isolated: a throwing callback is
   * logged at {@code WARNING} but does not prevent subsequent callbacks from firing.
   *
   * <p>Callbacks are not deduplicated — register the same callback twice and it fires twice.
   *
   * <p>The returned {@link Registration} lets callers deregister the callback once the work it
   * guards has completed — important for long-lived tokens (per-session) against which many short-
   * lived callers register (per-tool-call, per-execute). Without deregistration, callbacks would
   * accumulate in the token's list for the lifetime of the session even though each is inert after
   * its guarded work finished. Calling {@link Registration#remove()} after the token has already
   * fired is a safe no-op.
   *
   * @param callback the work to run on cancellation; non-null
   * @return a handle for removing this callback before cancellation fires
   * @throws NullPointerException if {@code callback} is null
   */
  public Registration onCancel(Runnable callback) {
    Objects.requireNonNull(callback, "callback must not be null");
    if (isCancelled()) {
      runSafely(callback);
      return Registration.NOOP;
    }
    callbacks.add(callback);
    if (isCancelled() && callbacks.remove(callback)) {
      // Lost the race — fire ourselves so the caller always sees a callback fire exactly once.
      runSafely(callback);
      return Registration.NOOP;
    }
    return new ListRegistration(callbacks, callback);
  }

  private void fireCallbacks() {
    for (var cb : callbacks) {
      runSafely(cb);
    }
    callbacks.clear();
  }

  /**
   * Visible-for-testing accessor exposing the count of currently-attached callbacks. Used to verify
   * that per-call sites (tool dispatch, question gateway) correctly invoke {@link
   * Registration#remove()} when their guarded work finishes, so callbacks do not accumulate on the
   * long-lived per-session token.
   *
   * @return the number of {@link #onCancel(Runnable)} registrations that have not yet fired or been
   *     removed
   */
  public int activeCallbackCountForTests() {
    return callbacks.size();
  }

  /**
   * Handle for a callback registered via {@link CancellationToken#onCancel(Runnable)}. Calling
   * {@link #remove()} detaches the callback so it will not fire on subsequent token cancellation.
   *
   * <p>Idempotent: calling {@code remove()} more than once, or after the token has already fired
   * the callback, is a safe no-op.
   */
  public sealed interface Registration permits ListRegistration, NoopRegistration {

    /** A pre-fired or never-registered handle — {@link #remove()} is a no-op. */
    Registration NOOP = new NoopRegistration();

    /** Detach this registration. Safe to call multiple times and after firing. */
    void remove();
  }

  private static final class ListRegistration implements Registration {
    private final List<Runnable> list;
    private final Runnable callback;

    ListRegistration(List<Runnable> list, Runnable callback) {
      this.list = list;
      this.callback = callback;
    }

    @Override
    public void remove() {
      list.remove(callback);
    }
  }

  private static final class NoopRegistration implements Registration {
    @Override
    public void remove() {
      // No callback to detach.
    }
  }

  private static void runSafely(Runnable callback) {
    try {
      callback.run();
    } catch (RuntimeException ex) {
      LOGGER.log(Level.WARNING, "cancellation callback threw", ex);
    }
  }

  /**
   * Throw if this token has been cancelled. Tools and other cooperative cancellation participants
   * should call this at safe points in their work.
   *
   * @throws CancellationException if cancelled; the exception message is the cancellation reason
   */
  public void throwIfCancelled() {
    var r = reason.get();
    if (r != null) {
      throw new CancellationException(r);
    }
  }
}
