/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.SessionSpansTest.call;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.OnStreamEventHook;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.test.SpanCapture;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * A run's span is a child of the span current where its {@code send} was called, and each step's
 * span is current while the step runs, so the application's own spans nest under it.
 */
final class SessionSpanContextTest {

  private static final Response<Void> DONE =
      Response.newBuilder().withContent("done").withFinishReason(FinishReason.STOP).build();
  private static final ContextKey<String> REQUEST = ContextKey.named("request");

  private final SpanCapture capture = new SpanCapture();

  private SessionOptions.Builder traced(Model model) {
    return EndingSessions.options(model).withOpenTelemetry(capture.openTelemetry());
  }

  /** Send {@code message} with {@code span} current on this thread. */
  private static CompletableFuture<ResultMessage> sendUnder(
      Span span, AgentSession session, String message) {
    try (var scope = span.makeCurrent()) {
      return session.send(message);
    } finally {
      span.end();
    }
  }

  @Test
  void eachRunIsAChildOfTheSpanCurrentWhereItsSendWasCalled() {
    var model = ScriptedModel.newBuilder().withTextTurn("a").withTextTurn("b").build();
    var first = capture.tracer().spanBuilder("request one").startSpan();
    var second = capture.tracer().spanBuilder("request two").startSpan();
    try (var session = AgentSession.open(traced(model).build())) {
      Await.value("the first run", sendUnder(first, session, "one"));
      Await.value("the second run", sendUnder(second, session, "two"));
    }

    var runs = capture.starting("invoke_agent ");
    assertEquals(first.getSpanContext().getSpanId(), runs.get(0).getParentSpanId());
    assertEquals(second.getSpanContext().getSpanId(), runs.get(1).getParentSpanId());
    assertEquals(first.getSpanContext().getTraceId(), runs.get(0).getTraceId());
    assertEquals(second.getSpanContext().getTraceId(), runs.get(1).getTraceId());
  }

  @Test
  void aRunSentWithNoCurrentSpanIsARootAndTheCallersContextIsUnchanged() {
    var before = Context.current();
    try (var session =
        AgentSession.open(
            traced(ScriptedModel.newBuilder().withTextTurn("done").build()).build())) {
      Await.value("the result", session.send("go"));
    }

    assertSame(before, Context.current());
    assertFalse(Span.current().getSpanContext().isValid());
    assertFalse(capture.run().getParentSpanContext().isValid());
  }

  @Test
  void aRunQueuedWhileTheLastOneEndsIsAChildOfItsOwnSendNotOfTheLastRun() {
    var model = ScriptedModel.newBuilder().withTextTurn("a").withTextTurn("b").build();
    var ending = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var first = capture.tracer().spanBuilder("request one").startSpan();
    var second = capture.tracer().spanBuilder("request two").startSpan();
    try (var session =
        AgentSession.open(EndingSessions.holdingItsEnd(traced(model), ending, release).build())) {
      var firstRun = sendUnder(first, session, "one");
      Await.latch("the first run to hold its end", ending);
      var secondRun = sendUnder(second, session, "two");
      release.countDown();
      Await.value("the first run", firstRun);
      Await.value("the second run", secondRun);
    }

    var runs = capture.starting("invoke_agent ");
    assertEquals(2, runs.size());
    assertEquals(first.getSpanContext().getSpanId(), runs.get(0).getParentSpanId());
    assertEquals(second.getSpanContext().getSpanId(), runs.get(1).getParentSpanId());
    assertNotEquals(runs.get(0).getSpanId(), runs.get(1).getParentSpanId());
  }

  @Test
  void aRunSentFromTheLastRunOfItsSessionIsAChildOfThatRunsParentNotOfTheRun() {
    var model = ScriptedModel.newBuilder().withTextTurn("a").withTextTurn("b").build();
    var opened = new CompletableFuture<AgentSession>();
    var followUp = new CompletableFuture<CompletableFuture<ResultMessage>>();
    OnStreamEventHook sending =
        (event, ctx) -> {
          if (event instanceof QueryEvent.RunEnded && !followUp.isDone()) {
            followUp.complete(opened.join().send("two"));
          }
        };
    var request = capture.tracer().spanBuilder("request").startSpan();
    try (var session = AgentSession.open(traced(model).withHook(sending).build())) {
      opened.complete(session);
      Await.value("the first run", sendUnder(request, session, "one"));
      Await.value("the follow-up run", Await.value("the follow-up's send", followUp));
    }

    var runs = capture.starting("invoke_agent ");
    assertEquals(2, runs.size());
    assertEquals(request.getSpanContext().getSpanId(), runs.get(1).getParentSpanId());
  }

  @Test
  void aRunAToolSendsToAnotherSessionIsAChildOfTheToolSpan() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(call("delegate", Map.of()))
            .withTextTurn("done")
            .build();
    var delegate =
        EndingSessions.binding(
            "delegate",
            (arguments, context) -> {
              var inner = ScriptedModel.newBuilder().withTextTurn("delegated").build();
              try (var session = AgentSession.open(traced(inner).build())) {
                Await.value("the delegated run", session.send("help"));
              }
              return ToolResult.success("delegated");
            });

    try (var session = AgentSession.open(traced(model).withTool(delegate).build())) {
      Await.value("the result", session.send("go"));
    }

    var tool = capture.only("execute_tool delegate");
    var runs = capture.starting("invoke_agent ");
    assertEquals(2, runs.size());
    assertEquals(tool.getSpanId(), runs.getFirst().getParentSpanId());
  }

  @Test
  void theRunsThreadRunsInTheCallersContextWithTheRunsSpanCurrent() {
    var seen = new CompletableFuture<String>();
    PreStopHook auditing =
        (response, ctx) -> {
          seen.complete(Context.current().get(REQUEST));
          capture.tracer().spanBuilder("audit").startSpan().end();
          return HookOutcome.cont();
        };
    var options =
        traced(ScriptedModel.newBuilder().withTextTurn("done").build()).withHook(auditing).build();
    try (var session = AgentSession.open(options);
        var scope = Context.root().with(REQUEST, "request-7").makeCurrent()) {
      Await.value("the result", session.send("go"));
    }

    assertEquals("request-7", Await.value("the hook's context value", seen));
    assertEquals(capture.run().getSpanId(), capture.only("audit").getParentSpanId());
  }

  @Test
  void spansTheSummaryModelStartsAreChildrenOfTheRunsSpan() {
    var summaryModel =
        EndingSessions.model(
            () -> {
              capture.tracer().spanBuilder("summary request").startSpan().end();
              return ModelStreams.of(ModelChunk.MessageStop.of(DONE));
            });
    var options =
        traced(twoTurns())
            .withTool(SessionSpansTest.tool("noop", "ok"))
            .withContextCompactor(SummarizingCompactor.newBuilder(summaryModel).build())
            .withLimits(SessionLimits.newBuilder().withMaxContextTokens(100L).build())
            .withTokenCounter(
                messages -> messages.stream().anyMatch(m -> m.role() == Role.TOOL) ? 96L : 10L);

    try (var session = AgentSession.open(options.build())) {
      Await.value("the result", session.send("go"));
    }

    var summaries = capture.named("summary request");
    assertFalse(summaries.isEmpty());
    for (var summary : summaries) {
      assertEquals(capture.run().getSpanId(), summary.getParentSpanId());
    }
  }

  @Test
  void aSpanClosingTheStreamOfAnInterruptedAttemptIsAChildOfTheChatSpan() {
    var closed = new CountDownLatch(1);
    var model =
        EndingSessions.model(
            () -> {
              Thread.currentThread().interrupt();
              return EndingSessions.stream(
                  () -> {
                    Uninterruptible.await(closed);
                    return false;
                  },
                  () -> {
                    capture.tracer().spanBuilder("http close").startSpan().end();
                    closed.countDown();
                  });
            });

    try (var session = AgentSession.open(traced(model).build())) {
      Await.value("the result", session.send("go"));
    }

    assertEquals(
        capture.only("chat scripted-stream").getSpanId(),
        capture.only("http close").getParentSpanId());
  }

  @Test
  void aSessionWithoutItsOwnOpenTelemetryEmitsThroughTheGlobalOne() {
    GlobalOpenTelemetry.resetForTest();
    GlobalOpenTelemetry.set(capture.openTelemetry());
    try (var session =
        AgentSession.open(
            EndingSessions.options(ScriptedModel.newBuilder().withTextTurn("done").build())
                .build())) {
      Await.value("the result", session.send("go"));
    } finally {
      GlobalOpenTelemetry.resetForTest();
    }

    assertEquals("invoke_agent helios", capture.run().getName());
  }

  @Test
  void aSpanTheToolStartsIsAChildOfItsToolSpan() {
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(call("lookup", Map.of()))
            .withTextTurn("done")
            .build();
    var lookup =
        EndingSessions.binding(
            "lookup",
            (arguments, context) -> {
              capture.tracer().spanBuilder("database query").startSpan().end();
              return ToolResult.success("found");
            });

    try (var session = AgentSession.open(traced(model).withTool(lookup).build())) {
      Await.value("the result", session.send("go"));
    }

    var tool = capture.only("execute_tool lookup");
    assertEquals(tool.getSpanId(), capture.only("database query").getParentSpanId());
  }

  @Test
  void spansTheModelClientStartsOpeningAndReadingItsStreamAreChildrenOfTheChatSpan() {
    var model =
        EndingSessions.model(
            () -> {
              capture.tracer().spanBuilder("http request").startSpan().end();
              return reading(ModelStreams.of(ModelChunk.MessageStop.of(DONE)));
            });

    try (var session = AgentSession.open(traced(model).build())) {
      Await.value("the result", session.send("go"));
    }

    var chat = capture.only("chat scripted-stream");
    assertEquals(chat.getSpanId(), capture.only("http request").getParentSpanId());
    assertEquals(chat.getSpanId(), capture.only("http body").getParentSpanId());
  }

  /** {@code stream}, starting and ending a span {@code http body} on its first read. */
  private CloseableIterator<ModelChunk> reading(CloseableIterator<ModelChunk> stream) {
    return new CloseableIterator<>() {
      private boolean read;

      @Override
      public boolean hasNext() {
        if (!read) {
          read = true;
          capture.tracer().spanBuilder("http body").startSpan().end();
        }
        return stream.hasNext();
      }

      @Override
      public ModelChunk next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return stream.next();
      }

      @Override
      public void close() {
        stream.close();
      }
    };
  }

  @Test
  void withoutAnSdkASessionRunsWithNoSpanCurrentInItsTools() {
    var current = new CompletableFuture<Span>();
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(call("probe", Map.of()))
            .withTextTurn("done")
            .build();
    var probe =
        EndingSessions.binding(
            "probe",
            (arguments, context) -> {
              current.complete(Span.current());
              return ToolResult.success("ok");
            });

    try (var session = AgentSession.open(EndingSessions.options(model).withTool(probe).build())) {
      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", session.send("go")));
    }

    assertFalse(Await.value("the tool's current span", current).getSpanContext().isValid());
  }

  static ScriptedModel twoTurns() {
    return ScriptedModel.newBuilder()
        .withToolCallsTurn(call("noop", Map.of()))
        .withTextTurn("done")
        .build();
  }
}
