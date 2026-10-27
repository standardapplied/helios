/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.session.ask.AskUserQuestionRequest;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class AgentSessionTest {

  /** Minimal AgentSession used to exercise default-method bodies on the interface. */
  private static final class StubSession implements AgentSession {

    final List<UserMessage> sent = new ArrayList<>();
    final CompletableFuture<ResultMessage> future = new CompletableFuture<>();

    @Override
    public CompletableFuture<ResultMessage> send(UserMessage message) {
      sent.add(message);
      return future;
    }

    @Override
    public void interrupt() {
      // not exercised by these interface-level tests
    }

    @Override
    public EventStream events() {
      return new EventStream() {
        @Override
        public Iterator<QueryEvent> iterator() {
          return Collections.emptyIterator();
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public String sessionId() {
      return "stub";
    }

    @Override
    public long currentTurnIndex() {
      return 0;
    }

    @Override
    public boolean isRunning() {
      return !sent.isEmpty() && !future.isDone();
    }

    @Override
    public void close() {
      // not exercised by these interface-level tests
    }

    @Override
    public void answer(String questionId, AskUserQuestionResponse response) {
      // not exercised by these interface-level tests
    }

    @Override
    public List<AskUserQuestionRequest> pendingQuestions() {
      return List.of();
    }

    @Override
    public <T> T runBlocking(UserMessage message, OutputSchema<T> schema) {
      throw new UnsupportedOperationException("not exercised by these interface-level tests");
    }
  }

  @Test
  void sendStringConvertsToUserMessage() {
    var s = new StubSession();
    assertSame(s.future, s.send("hello world"));
    assertEquals(1, s.sent.size());
    assertEquals("hello world", s.sent.get(0).text());
  }

  @Test
  void runBlockingSendsAndAwaitsResult() {
    var s = new StubSession();
    var expected =
        new ResultMessage.Success(
            "stub", "run-1", "done", Usage.of(0, 0), CostEstimate.zero(), Duration.ZERO, List.of());
    s.future.complete(expected);
    var result = s.runBlocking(UserMessage.text("hi"));
    assertSame(expected, result);
    assertEquals("hi", s.sent.get(0).text());
  }
}
