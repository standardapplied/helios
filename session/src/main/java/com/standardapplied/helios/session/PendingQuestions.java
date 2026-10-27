/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.session.ask.AskUserQuestionRequest;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import com.standardapplied.helios.session.ask.QuestionGateway;
import com.standardapplied.helios.session.loop.SessionState;
import java.time.InstantSource;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * The session's open {@code AskUserQuestion} questions: the gateway the tool asks through, which
 * emits {@link QueryEvent.QuestionAsked} and blocks on a future keyed by question id, the listing
 * of the questions still open, and the answer and cancellation that complete those futures.
 *
 * <p>A question belongs to the run that asks it, and one asked after that run ended is never posed.
 * Cancellation is wired via {@link CancellationToken#onCancel(Runnable)} on that run's token — when
 * the run ends or is cancelled, the registered callback completes the pending future with a {@link
 * CancellationException}, waking {@code future.get()} immediately, and the question is no longer
 * pending. No polling.
 *
 * <p>{@link CompletableFuture#get()} special-cases {@code CancellationException}-shaped results and
 * re-throws them directly rather than wrapping in {@code ExecutionException}, so the only checked
 * throwables we have to propagate are {@link InterruptedException} and {@link
 * CancellationException}. A defensive {@code ExecutionException} catch covers the theoretical case
 * where some future caller completes the future with a non-cancellation throwable; we re-wrap as
 * cancellation so the agent loop's tool dispatcher sees a coherent failure.
 */
final class PendingQuestions implements QuestionGateway {

  private final Supplier<SessionState> runs;
  private final SessionEventPublisher events;
  private final InstantSource clock;
  private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

  /**
   * @param runs the state of the run asking, whose token a question observes
   */
  PendingQuestions(Supplier<SessionState> runs, SessionEventPublisher events, InstantSource clock) {
    this.runs = runs;
    this.events = events;
    this.clock = clock;
  }

  @Override
  public AskUserQuestionResponse ask(AskUserQuestionRequest request)
      throws InterruptedException, CancellationException {
    Objects.requireNonNull(request, "request must not be null");
    var state = runs.get();
    state.cancellation().throwIfCancelled();
    var future = new CompletableFuture<AskUserQuestionResponse>();
    pending.put(request.questionId(), new Pending(request, future));
    var cancelRegistration =
        state
            .cancellation()
            .onCancel(
                () ->
                    future.completeExceptionally(
                        new CancellationException(
                            state.cancellation().reason().orElse("run cancelled"))));
    try {
      events.emit(
          new QueryEvent.QuestionAsked(
              state.sessionId(),
              state.runId(),
              state.currentTurnIndex(),
              clock.instant(),
              request));
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

  /** The requests of the questions asked and neither answered nor ended yet. */
  List<AskUserQuestionRequest> requests() {
    return pending.values().stream().map(Pending::request).toList();
  }

  /**
   * Complete the pending question with {@code response}.
   *
   * @throws IllegalArgumentException if no question with {@code questionId} is pending
   */
  void answer(String questionId, AskUserQuestionResponse response) {
    var question = pending.remove(questionId);
    if (question == null) {
      throw new IllegalArgumentException(
          "no pending question with id '" + questionId + "' — already answered or unknown");
    }
    question.future().complete(response);
  }

  /** An open question and the future its asking thread waits on. */
  private record Pending(
      AskUserQuestionRequest request, CompletableFuture<AskUserQuestionResponse> future) {}
}
