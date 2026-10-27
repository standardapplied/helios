/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.session.AgentSessionImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * How a session ends early: its one cancellation path, and the one sink of every {@link Error}
 * caught on a thread the session runs work on — the loop, tool workers, cancellation callbacks of
 * the session's token and of each tool call's token, the model stream's reader and its close, the
 * compaction summary call, and {@code onSessionStart} and {@code onSessionEnd}. Recording an {@code
 * Error} cancels the session. The session settles its result by {@link #seal() sealing} the sink;
 * an {@code Error} recorded after that is logged at {@code SEVERE} and changes nothing. Threads the
 * session does not own are outside it: their {@code Error}s reach their thread's uncaught-exception
 * handler. Safe for concurrent use.
 */
public final class SessionEnding {

  private static final Logger LOGGER = Logger.getLogger(AgentSessionImpl.class.getName());

  private final String sessionId;
  private final CancellationToken token;
  private final List<Error> errors = new ArrayList<>();
  private boolean sealed;
  private CompletableFuture<Void> cancelling;
  private Consumer<Error> onFirstFailure = error -> {};

  SessionEnding(String sessionId, CancellationToken token) {
    this.sessionId = sessionId;
    this.token = token;
  }

  /**
   * Cancel the session's token, the one way the session is cancelled: by its close, its teardown
   * and a recorded {@link Error}. The token of the run in progress follows it. The token is
   * cancelled when this returns; its callbacks run on a thread of their own, each {@code Error}
   * they throw recorded by {@link #fail}. The first call's {@code reason} wins, and every call
   * returns the same future.
   *
   * @param reason why the session is cancelled; non-blank
   * @return a future that completes once every callback of the token has run
   */
  public synchronized CompletableFuture<Void> cancel(String reason) {
    if (cancelling == null) {
      cancelling = token.cancelAsync(reason, this::fail);
    }
    return cancelling;
  }

  /**
   * Call {@code listener} with the first {@link Error} recorded, on the thread that records it,
   * before the session is cancelled; at once if one already is.
   *
   * @param listener what the session does once an {@code Error} failed it; non-null
   */
  public void onFirstFailure(Consumer<Error> listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    Optional<Error> recorded;
    synchronized (this) {
      onFirstFailure = listener;
      recorded = first();
    }
    recorded.ifPresent(listener);
  }

  /**
   * Record {@code error}, once by identity, and cancel the session with a reason naming its class;
   * or, once sealed, log it at {@code SEVERE}. The first {@code Error} recorded first reaches the
   * {@link #onFirstFailure} listener.
   *
   * @param error the error a thread of the session caught; non-null
   * @throws NullPointerException if {@code error} is null
   */
  public void fail(Error error) {
    Objects.requireNonNull(error, "error must not be null");
    boolean recorded;
    Consumer<Error> listener = null;
    synchronized (this) {
      recorded = !sealed;
      if (recorded && errors.isEmpty()) {
        listener = onFirstFailure;
      }
      if (recorded && errors.stream().noneMatch(kept -> kept == error)) {
        errors.add(error);
      }
    }
    if (listener != null) {
      listener.accept(error);
    }
    if (recorded) {
      cancel("host error " + error.getClass().getName());
    } else {
      LOGGER.log(Level.SEVERE, "an Error reached session " + sessionId + " after it ended", error);
    }
  }

  /**
   * Record the host {@link Error} {@code thrown} is or carries, as {@link #fail} does: {@code
   * thrown} itself, or the {@code Error} cause of a {@link CompletionException} or {@link
   * ExecutionException}, such as a {@code join} or {@code get} of a failed future throws.
   *
   * @param thrown what a thread of the session caught; non-null
   * @return whether {@code thrown} is or carries an {@code Error}
   * @throws NullPointerException if {@code thrown} is null
   */
  public boolean failWith(Throwable thrown) {
    Objects.requireNonNull(thrown, "thrown must not be null");
    var error =
        thrown instanceof Error itself
            ? itself
            : (thrown instanceof CompletionException || thrown instanceof ExecutionException)
                    && thrown.getCause() instanceof Error cause
                ? cause
                : null;
    if (error != null) {
      fail(error);
    }
    return error != null;
  }

  /**
   * The first {@link Error} recorded so far, without sealing the sink: a run that ends with one
   * fails with it before the session tears down.
   *
   * @return the first recorded {@code Error}, or empty when none was recorded
   */
  public synchronized Optional<Error> first() {
    return errors.stream().findFirst();
  }

  /**
   * Seal the sink: from now on {@link #fail} only logs. Called once, by the session's settlement.
   *
   * @return the first recorded {@link Error}, every later one added to it as suppressed, or empty
   *     when none was recorded
   */
  public synchronized Optional<Error> seal() {
    sealed = true;
    if (errors.isEmpty()) {
      return Optional.empty();
    }
    var first = errors.getFirst();
    errors.stream().skip(1).forEach(first::addSuppressed);
    return Optional.of(first);
  }
}
