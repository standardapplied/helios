/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.ask.AskUserQuestionOption;
import com.standardapplied.helios.session.ask.AskUserQuestionRequest;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import com.standardapplied.helios.session.loop.SessionState;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;

final class PendingQuestionsTest {

  private static final InstantSource CLOCK =
      InstantSource.fixed(Instant.parse("2026-10-06T00:00:00Z"));
  private static final AskUserQuestionRequest QUESTION =
      new AskUserQuestionRequest(
          "q1",
          "Pick",
          "Which one?",
          List.of(
              new AskUserQuestionOption("a", "first"), new AskUserQuestionOption("b", "second")),
          false);

  @Test
  void cancelAllFailsAPendingQuestionAndForgetsIt() {
    var state = new SessionState("pending", new CancellationToken(), CLOCK);
    var events = new SessionEventPublisher("pending");
    var asked = new CompletableFuture<AskUserQuestionRequest>();
    events.subscribe(new QuestionWatcher(asked));
    var questions = new PendingQuestions(state, events, CLOCK);
    var asking = new FutureTask<>(() -> questions.ask(QUESTION));
    Thread.ofVirtual().start(asking);
    Await.value("the question to be asked", asked);

    questions.cancelAll();

    var failure = assertInstanceOf(CancellationException.class, Await.failure("ask", asking));
    var cause = assertInstanceOf(CancellationException.class, failure.getCause());
    assertEquals("session cancelled", cause.getMessage());
    var unknown =
        assertThrows(
            IllegalArgumentException.class,
            () -> questions.answer("q1", AskUserQuestionResponse.single("q1", "a")));
    assertEquals(
        "no pending question with id 'q1' — already answered or unknown", unknown.getMessage());
    events.close();
  }

  private record QuestionWatcher(CompletableFuture<AskUserQuestionRequest> asked)
      implements Flow.Subscriber<QueryEvent> {

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(QueryEvent event) {
      if (event instanceof QueryEvent.QuestionAsked question) {
        asked.complete(question.request());
      }
    }

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onComplete() {}
  }
}
