/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.core.common.CostEstimate;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.session.ask.AskUserQuestionResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

final class AgentSessionTest {

  /** Minimal AgentSession used to exercise default-method bodies on the interface. */
  private static final class StubSession implements AgentSession {

    final List<UserMessage> sent = new ArrayList<>();
    final List<String> interrupts = new ArrayList<>();
    int closes = 0;
    boolean started = false;
    final CompletableFuture<ResultMessage> future = new CompletableFuture<>();

    @Override
    public void send(UserMessage message) {
      sent.add(message);
      started = true;
    }

    @Override
    public void interrupt(String reason) {
      interrupts.add(reason);
    }

    @Override
    public Flow.Publisher<QueryEvent> events() {
      return subscriber -> {};
    }

    @Override
    public CompletableFuture<ResultMessage> result() {
      return future;
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
    public void close() {
      closes++;
    }

    @Override
    public void answer(String questionId, AskUserQuestionResponse response) {
      // not exercised by these interface-level tests
    }
  }

  @Test
  void sendStringConvertsToUserMessage() {
    var s = new StubSession();
    s.send("hello world");
    assertEquals(1, s.sent.size());
    assertEquals("hello world", s.sent.get(0).text());
  }

  @Test
  void runBlockingSendsAndAwaitsResult() {
    var s = new StubSession();
    var expected =
        new ResultMessage.Success(
            "stub", "done", Usage.of(0, 0), CostEstimate.zero(), Duration.ZERO);
    s.future.complete(expected);
    var result = s.runBlocking(UserMessage.text("hi"));
    assertSame(expected, result);
    assertEquals("hi", s.sent.get(0).text());
  }

  /** Trivial record used to build an OutputSchema for the typed-runBlocking tests. */
  public record StubOutput(String answer) {}

  @Test
  void typedRunBlockingRejectsNullMessage() {
    var s = new StubSession();
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> s.runBlocking(null, OutputSchema.of(StubOutput.class)));
    assertEquals("message must not be null", ex.getMessage());
  }

  @Test
  void typedRunBlockingRejectsNullSchema() {
    var s = new StubSession();
    var ex =
        assertThrows(NullPointerException.class, () -> s.runBlocking(UserMessage.text("hi"), null));
    assertEquals("schema must not be null", ex.getMessage());
  }
}
