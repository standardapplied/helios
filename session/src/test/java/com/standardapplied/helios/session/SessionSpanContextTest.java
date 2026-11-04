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
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.test.SpanCapture;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * A run's span is a child of the span current where its {@code send} was called, and each step's
 * span is current while the step runs, so the application's own spans nest under it; what the
 * tracing pipeline does never changes a run's result.
 */
final class SessionSpanContextTest {

  private static final Response<Void> DONE =
      Response.newBuilder().withContent("done").withFinishReason(FinishReason.STOP).build();

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
  void anExporterThatThrowsLeavesTheRunsResultAsWithoutTracing() {
    var plain =
        assertInstanceOf(
            ResultMessage.Success.class,
            runOnce(EndingSessions.options(twoTurns()), new CountDownLatch(0)));
    var exported = new CountDownLatch(1);
    var sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(new Throwing(exported)))
                    .build())
            .build();

    var traced =
        assertInstanceOf(
            ResultMessage.Success.class,
            runOnce(EndingSessions.options(twoTurns()).withOpenTelemetry(sdk), exported));

    assertEquals(plain.result(), traced.result());
    assertEquals(plain.usage(), traced.usage());
    assertEquals(plain.cost(), traced.cost());
  }

  private static ScriptedModel twoTurns() {
    return ScriptedModel.newBuilder()
        .withToolCallsTurn(call("noop", Map.of()))
        .withTextTurn("done")
        .build();
  }

  private static ResultMessage runOnce(SessionOptions.Builder options, CountDownLatch exported) {
    try (var session =
        AgentSession.open(options.withTool(SessionSpansTest.tool("noop", "ok")).build())) {
      var result = Await.value("the result", session.send("go"));
      Await.latch("the exporter to have thrown", exported);
      return result;
    }
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

  @Test
  void anErrorASpanProcessorThrowsEndingTheRunsSpanFailsTheRunWithThatError() {
    var failure = new SessionSpanEndingsTest.HostFailure();
    var sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder().addSpanProcessor(new FailingRunEnd(failure)).build())
            .build();
    var options =
        EndingSessions.options(ScriptedModel.newBuilder().withTextTurn("done").build())
            .withOpenTelemetry(sdk);

    try (var session = AgentSession.open(options.build())) {
      assertSame(failure, Await.failure("the result to fail", session.send("go")));
    }
  }

  @Test
  void anErrorASpanProcessorThrowsAfterACloseReturnedStillFailsTheRunWithThatError() {
    var failure = new SessionSpanEndingsTest.HostFailure();
    var entered = new CountDownLatch(1);
    var released = new CountDownLatch(1);
    var sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(new FailingRunEnd(failure, entered, released))
                    .build())
            .build();
    var options =
        EndingSessions.options(ScriptedModel.newBuilder().withTextTurn("done").build())
            .withLimits(EndingSessions.grace())
            .withOpenTelemetry(sdk);
    var session = AgentSession.open(options.build());
    var result = session.send("go");
    Await.latch("the run's span to end", entered);

    session.close();
    assertFalse(result.isDone(), "the close returned before the run's span ended");
    released.countDown();

    assertSame(failure, Await.failure("the result to fail", result));
  }

  @Test
  void anErrorStartingTheRunsSpanFailsTheRunWithThatError() {
    var failure = new SessionSpanEndingsTest.HostFailure();
    var sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder().addSpanProcessor(new FailingRunStart(failure)).build())
            .build();
    PreStopHook never =
        (response, ctx) -> {
          throw new AssertionError("the loop ran");
        };
    var options =
        EndingSessions.options(ScriptedModel.newBuilder().withTextTurn("done").build())
            .withHook(never)
            .withOpenTelemetry(sdk);

    try (var session = AgentSession.open(options.build())) {
      assertSame(failure, Await.failure("the result to fail", session.send("go")));
    }
  }

  /** An exporter that throws from every export, counting down {@code exported} first. */
  private record Throwing(CountDownLatch exported) implements SpanExporter {

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
      exported.countDown();
      throw new IllegalStateException("export failed");
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }

  /** Throws {@code failure} when a run's span ends, once {@code released} opens. */
  private record FailingRunEnd(Error failure, CountDownLatch entered, CountDownLatch released)
      implements SpanProcessor {

    FailingRunEnd(Error failure) {
      this(failure, new CountDownLatch(1), new CountDownLatch(0));
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {}

    @Override
    public boolean isStartRequired() {
      return false;
    }

    @Override
    public void onEnd(ReadableSpan span) {
      if (span.getName().startsWith("invoke_agent ")) {
        entered.countDown();
        Uninterruptible.await(released);
        throw failure;
      }
    }

    @Override
    public boolean isEndRequired() {
      return true;
    }
  }

  /** Throws {@code failure} when a run's span starts. */
  private record FailingRunStart(Error failure) implements SpanProcessor {

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
      if (span.getName().startsWith("invoke_agent ")) {
        throw failure;
      }
    }

    @Override
    public boolean isStartRequired() {
      return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {}

    @Override
    public boolean isEndRequired() {
      return false;
    }
  }
}
