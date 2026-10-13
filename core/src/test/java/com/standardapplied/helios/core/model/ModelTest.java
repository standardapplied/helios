/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ModelTest {

  record Answer(String answer) {}

  @Test
  void chatAddsTheStreamUpToItsResponse() {
    var call = ToolCall.newBuilder().withId("c1").withName("lookup").build();
    var cite = Citation.of("https://example.com", "snippet");
    var usage = Response.Usage.of(9, 4);
    var model =
        streaming(
            new ModelChunk.ThinkingDelta("Hm"),
            new ModelChunk.ThinkingDelta("m."),
            new ModelChunk.TextDelta("Let me "),
            new ModelChunk.TextDelta("check."),
            new ModelChunk.ToolUseStart("c1", "lookup"),
            new ModelChunk.ToolUseDelta("c1", "{}"),
            new ModelChunk.UsageDelta(usage),
            new ModelChunk.ToolUseStop(call),
            new ModelChunk.MessageStop("TOOL_CALLS", usage, Map.of("k", "v"), List.of(cite)));

    var response = model.chat(List.of(), List.of());

    assertEquals("Let me check.", response.content());
    assertEquals("Hmm.", response.thinking());
    assertEquals(List.of(call), response.toolCalls());
    assertEquals(FinishReason.TOOL_CALLS, response.finishReason());
    assertEquals(usage, response.usage());
    assertEquals(Map.of("k", "v"), response.metadata());
    assertEquals(List.of(cite), response.citations());
  }

  @Test
  void chatOnAStreamWithoutAStopIsAPlainStop() {
    var response = streaming(new ModelChunk.TextDelta("hi")).chat(List.of(), List.of());

    assertEquals("hi", response.content());
    assertNull(response.thinking());
    assertEquals(FinishReason.STOP, response.finishReason());
    assertEquals(Response.Usage.of(0, 0), response.usage());
    assertEquals(Map.of(), response.metadata());
    assertEquals(List.of(), response.citations());
  }

  @Test
  void aProvidersOwnStopReasonReadsAsAPlainStop() {
    var stop = new ModelChunk.MessageStop("end_turn", Response.Usage.of(1, 1), Map.of());

    assertEquals(FinishReason.STOP, stop.finishReason());
    assertEquals(FinishReason.STOP, streaming(stop).chat(List.of(), List.of()).finishReason());
  }

  @Test
  void chatThrowsTheStreamsUncheckedFailureAsItCame() {
    var refused = new IllegalStateException("refused");

    assertSame(refused, assertThrows(IllegalStateException.class, () -> failing(refused)));
  }

  @Test
  void chatThrowsTheStreamsErrorAsItCame() {
    var fatal = new StackOverflowError("deep");

    assertSame(fatal, assertThrows(StackOverflowError.class, () -> failing(fatal)));
  }

  @Test
  void chatWrapsTheStreamsCheckedFailure() {
    var reset = new IOException("reset");

    var thrown = assertThrows(IllegalStateException.class, () -> failing(reset));

    assertSame(reset, thrown.getCause());
  }

  @Test
  void anInterruptWhileWaitingCancelsTheStreamAndRestoresTheInterrupt() {
    var subscribed = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    Model model =
        model(
            (cancellation) ->
                subscriber ->
                    subscriber.onSubscribe(
                        new Flow.Subscription() {
                          @Override
                          public void request(long n) {
                            subscribed.countDown();
                          }

                          @Override
                          public void cancel() {
                            cancelled.countDown();
                          }
                        }));
    var interruptRestored = new boolean[1];
    var chat =
        new FutureTask<>(
            () -> {
              try {
                return model.chat(List.of(), List.of());
              } finally {
                interruptRestored[0] = Thread.currentThread().isInterrupted();
              }
            });
    var caller = Thread.ofPlatform().start(chat);
    Await.latch("the chat subscribed", subscribed);
    caller.interrupt();

    assertInstanceOf(CancellationException.class, Await.failure("the chat", chat));
    Await.latch("the stream cancelled", cancelled);
    assertTrue(interruptRestored[0]);
  }

  @Test
  void anInterruptWhoseCancelFailsStillRestoresTheInterrupt() {
    var subscribed = new CountDownLatch(1);
    Model model =
        model(
            (cancellation) ->
                subscriber ->
                    subscriber.onSubscribe(
                        new Flow.Subscription() {
                          @Override
                          public void request(long n) {
                            subscribed.countDown();
                          }

                          @Override
                          public void cancel() {
                            throw new IllegalStateException("close failed");
                          }
                        }));
    var interruptRestored = new boolean[1];
    var chat =
        new FutureTask<>(
            () -> {
              try {
                return model.chat(List.of(), List.of());
              } finally {
                interruptRestored[0] = Thread.currentThread().isInterrupted();
              }
            });
    var caller = Thread.ofPlatform().start(chat);
    Await.latch("the chat subscribed", subscribed);
    caller.interrupt();

    var thrown = assertInstanceOf(IllegalStateException.class, Await.failure("the chat", chat));
    assertEquals("close failed", thrown.getMessage());
    assertTrue(interruptRestored[0]);
  }

  @Test
  void anInterruptBeforeTheStreamSubscribesCancelsTheLateSubscriptionWithoutARequest() {
    var subscribers = new LinkedBlockingQueue<Flow.Subscriber<? super ModelChunk>>();
    Model model = model((cancellation) -> subscribers::add);
    var chat = new FutureTask<>(() -> model.chat(List.of(), List.of()));
    var caller = Thread.ofPlatform().start(chat);
    var late = Await.next("the chat subscribed", subscribers);
    caller.interrupt();
    assertInstanceOf(CancellationException.class, Await.failure("the chat", chat));

    var requests = new AtomicInteger();
    var cancels = new AtomicInteger();
    late.onSubscribe(
        new Flow.Subscription() {
          @Override
          public void request(long n) {
            requests.incrementAndGet();
          }

          @Override
          public void cancel() {
            cancels.incrementAndGet();
          }
        });

    assertEquals(0, requests.get());
    assertEquals(1, cancels.get());
  }

  @Test
  void structuredOutputIsUnsupportedByDefault() {
    var model = streaming();
    var schema = OutputSchema.of(Answer.class);
    var token = new CancellationToken();

    assertThrows(
        UnsupportedOperationException.class, () -> model.chat(List.of(), List.of(), schema));
    assertThrows(
        UnsupportedOperationException.class,
        () -> model.chatStream(List.of(), List.of(), schema, token));
  }

  @Test
  void identityDefaultsAndCloseAreSafe() {
    try (var model = streaming()) {
      assertEquals(0, model.contextWindow());
      assertEquals(0, model.maxOutputTokens());
      model.close();
    }
  }

  @Test
  void closeableIteratorOfWrapsPlainIterator() {
    var closeable = CloseableIterator.of(List.of("a", "b").iterator());

    assertTrue(closeable.hasNext());
    assertEquals("a", closeable.next());
    assertEquals("b", closeable.next());
    assertFalse(closeable.hasNext());
    closeable.close();
  }

  private static void failing(Throwable failure) {
    model(
            cancellation ->
                subscriber -> {
                  subscriber.onSubscribe(
                      new Flow.Subscription() {
                        @Override
                        public void request(long n) {
                          subscriber.onError(failure);
                        }

                        @Override
                        public void cancel() {}
                      });
                })
        .chat(List.of(), List.of());
  }

  private static Model streaming(ModelChunk... chunks) {
    return model(
        cancellation ->
            new IteratorPublisher<>(
                () -> CloseableIterator.of(List.of(chunks).iterator()), cancellation));
  }

  private static Model model(Function<CancellationToken, Flow.Publisher<ModelChunk>> stream) {
    return new Model() {
      @Override
      public Flow.Publisher<ModelChunk> chatStream(
          List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
        return stream.apply(cancellation);
      }

      @Override
      public String id() {
        return "test";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }
}
