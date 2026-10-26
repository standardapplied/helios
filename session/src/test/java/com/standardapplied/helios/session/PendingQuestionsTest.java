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
import com.standardapplied.helios.session.test.EventCollector;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.AfterEach;
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

  private final SessionState state = new SessionState("pending", new CancellationToken(), CLOCK);
  private final SessionEventPublisher events = new SessionEventPublisher("pending");
  private final PendingQuestions questions = new PendingQuestions(state, events, CLOCK);

  @AfterEach
  void closeEvents() {
    events.close();
  }

  @Test
  void anAskedQuestionIsListedUntilItIsAnswered() {
    var asking = asking();

    assertEquals(List.of(QUESTION), questions.requests());
    questions.answer("q1", AskUserQuestionResponse.single("q1", "a"));

    assertEquals(AskUserQuestionResponse.single("q1", "a"), Await.value("ask", asking));
    assertEquals(List.of(), questions.requests());
  }

  @Test
  void cancellingTheSessionFailsAPendingQuestionAndForgetsIt() {
    var asking = asking();

    state.cancellation().cancel("session closed");

    var failure = assertInstanceOf(CancellationException.class, Await.failure("ask", asking));
    var cause = assertInstanceOf(CancellationException.class, failure.getCause());
    assertEquals("session closed", cause.getMessage());
    assertEquals(List.of(), questions.requests());
    var unknown =
        assertThrows(
            IllegalArgumentException.class,
            () -> questions.answer("q1", AskUserQuestionResponse.single("q1", "a")));
    assertEquals(
        "no pending question with id 'q1' — already answered or unknown", unknown.getMessage());
  }

  /** Asks {@link #QUESTION} on a thread of its own and returns once the question was emitted. */
  private FutureTask<AskUserQuestionResponse> asking() {
    var asked = new CompletableFuture<AskUserQuestionRequest>();
    new EventCollector(
            event -> {
              if (event instanceof QueryEvent.QuestionAsked question) {
                asked.complete(question.request());
              }
            })
        .read(events.open());
    var asking = new FutureTask<>(() -> questions.ask(QUESTION));
    Thread.ofVirtual().start(asking);
    Await.value("the question to be asked", asked);
    return asking;
  }
}
