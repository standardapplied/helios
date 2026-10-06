/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.session.ask.AskUserQuestionRequest;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import com.standardapplied.helios.session.ask.QuestionGateway;
import com.standardapplied.helios.session.loop.SessionState;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * The session's open {@code AskUserQuestion} questions: the gateway the tool asks through, which
 * emits {@link QueryEvent.QuestionAsked} and blocks on a future keyed by question id, and the
 * answer and cancellation that complete those futures.
 *
 * <p>Cancellation is wired via {@link CancellationToken#onCancel(Runnable)} — when the session
 * cancels, the registered callback completes the pending future with a {@link
 * CancellationException}, waking {@code future.get()} immediately. No polling.
 *
 * <p>{@link CompletableFuture#get()} special-cases {@code CancellationException}-shaped results and
 * re-throws them directly rather than wrapping in {@code ExecutionException}, so the only checked
 * throwables we have to propagate are {@link InterruptedException} and {@link
 * CancellationException}. A defensive {@code ExecutionException} catch covers the theoretical case
 * where some future caller completes the future with a non-cancellation throwable; we re-wrap as
 * cancellation so the agent loop's tool dispatcher sees a coherent failure.
 */
final class PendingQuestions implements QuestionGateway {

  private final SessionState state;
  private final SessionEventPublisher events;
  private final InstantSource clock;
  private final ConcurrentHashMap<String, CompletableFuture<AskUserQuestionResponse>> pending =
      new ConcurrentHashMap<>();

  PendingQuestions(SessionState state, SessionEventPublisher events, InstantSource clock) {
    this.state = state;
    this.events = events;
    this.clock = clock;
  }

  @Override
  public AskUserQuestionResponse ask(AskUserQuestionRequest request)
      throws InterruptedException, CancellationException {
    Objects.requireNonNull(request, "request must not be null");
    var future = new CompletableFuture<AskUserQuestionResponse>();
    pending.put(request.questionId(), future);
    // Capture the Registration so we can remove the callback in finally. Without this every
    // AskUserQuestion call accumulates a permanent closure on the session's long-lived
    // CancellationToken — a session with N questions over its life leaks N callbacks (each
    // pinning the completed future via the closure capture). Mirrors the pattern in
    // LocalProcessExecutionProvider.runProcess.
    var cancelRegistration =
        state
            .cancellation()
            .onCancel(
                () ->
                    future.completeExceptionally(
                        new CancellationException(
                            state.cancellation().reason().orElse("session cancelled"))));
    try {
      events.emit(
          new QueryEvent.QuestionAsked(
              state.sessionId(), state.currentTurnIndex(), clock.instant(), request));
      return future.get();
    } catch (ExecutionException e) {
      var cause = e.getCause();
      throw new CancellationException(
          "question "
              + request.questionId()
              + " failed: "
              + (cause == null ? "no cause" : cause.getMessage()));
    } finally {
      cancelRegistration.remove();
      pending.remove(request.questionId());
    }
  }

  /**
   * Complete the pending question with {@code response}.
   *
   * @throws IllegalArgumentException if no question with {@code questionId} is pending
   */
  void answer(String questionId, AskUserQuestionResponse response) {
    var future = pending.remove(questionId);
    if (future == null) {
      throw new IllegalArgumentException(
          "no pending question with id '" + questionId + "' — already answered or unknown");
    }
    future.complete(response);
  }

  /** Fail every pending question with a {@link CancellationException}. */
  void cancelAll() {
    // Snapshot to avoid concurrent-mutation surprises while completing.
    for (var entry : new ArrayList<>(pending.entrySet())) {
      var future = pending.remove(entry.getKey());
      if (future != null) {
        future.completeExceptionally(new CancellationException("session cancelled"));
      }
    }
  }
}
