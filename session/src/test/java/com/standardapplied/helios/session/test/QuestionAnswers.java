/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.test;

import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import java.util.function.Consumer;

/**
 * Reactions to a session's events, for a {@link CollectingSubscriber}, that answer the questions
 * the session asks while its loop waits on them.
 */
public final class QuestionAnswers {

  private QuestionAnswers() {}

  /**
   * Answers every {@link QueryEvent.QuestionAsked} of {@code session} by selecting the option
   * labelled {@code label}, on the thread that delivers the event.
   */
  public static Consumer<QueryEvent> selecting(AgentSession session, String label) {
    return event -> {
      if (event instanceof QueryEvent.QuestionAsked asked) {
        var questionId = asked.request().questionId();
        session.answer(questionId, AskUserQuestionResponse.single(questionId, label));
      }
    };
  }
}
