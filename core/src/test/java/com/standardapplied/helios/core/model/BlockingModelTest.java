/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.StreamRead;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.Tool;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class BlockingModelTest {

  record Answer(String answer) {}

  private static final Response.Usage USAGE = Response.Usage.of(5, 3);

  private final AtomicInteger calls = new AtomicInteger();

  @Test
  void theBlockingCallRunsOnTheFirstHasNextNotBefore() {
    var model = answering(() -> reply("hi").build());

    var stream = model.chatStream(List.of(), List.of());
    var callsBeforeRead = calls.get();

    assertEquals(0, callsBeforeRead);
    assertEquals(
        List.of(
            new ModelChunk.TextDelta("hi"), new ModelChunk.MessageStop("STOP", USAGE, Map.of())),
        StreamRead.of(stream).items());
    assertEquals(1, calls.get());
  }

  @Test
  void theResponseIsYieldedAsThinkingTextToolPairsThenTheStop() {
    var call = ToolCall.newBuilder().withId("c1").withName("lookup").build();
    var cite = Citation.of("https://example.com", "snippet");
    var model =
        answering(
            () ->
                reply("Let me check.")
                    .withThinking("Hmm.")
                    .withToolCalls(List.of(call))
                    .withFinishReason(FinishReason.TOOL_CALLS)
                    .withMetadata(Map.of("k", "v"))
                    .withCitations(List.of(cite))
                    .build());

    assertEquals(
        List.of(
            new ModelChunk.ThinkingDelta("Hmm."),
            new ModelChunk.TextDelta("Let me check."),
            new ModelChunk.ToolUseStart("c1", "lookup"),
            new ModelChunk.ToolUseStop(call),
            new ModelChunk.MessageStop("TOOL_CALLS", USAGE, Map.of("k", "v"), List.of(cite))),
        drain(model));
  }

  @Test
  void anEmptyResponseIsOnlyItsStopWithDefaultsForWhatItLacks() {
    var bare = new Response<Void>(null, null, List.of(), null, null, "", null, null);

    assertEquals(
        List.of(new ModelChunk.MessageStop("STOP", Response.Usage.of(0, 0), Map.of(), List.of())),
        drain(answering(() -> bare)));
    assertEquals(
        List.of(new ModelChunk.MessageStop("STOP", USAGE, Map.of())),
        drain(answering(() -> reply("").build())));
  }

  @Test
  void aStructuredStreamRunsTheSchemaCall() {
    var model =
        new BlockingModel() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new AssertionError("a structured stream must make the schema call");
          }

          @Override
          public <T> Response<T> chat(
              List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
            return Response.newBuilder(outputSchema.type())
                .withContent("{\"answer\":\"42\"}")
                .withFinishReason(FinishReason.STOP)
                .withUsage(USAGE)
                .build();
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

    var chunks =
        StreamRead.of(model.chatStream(List.of(), List.of(), OutputSchema.of(Answer.class)))
            .items();

    assertEquals(new ModelChunk.TextDelta("{\"answer\":\"42\"}"), chunks.getFirst());
    assertThrows(NullPointerException.class, () -> model.chatStream(List.of(), List.of(), null));
  }

  @Test
  void whatTheCallThrowsIsThrownFromHasNextAsItself() {
    var refused = new IllegalStateException("refused");
    var fatal = new OutOfMemoryError("host");

    var stream =
        answering(
                () -> {
                  throw refused;
                })
            .chatStream(List.of(), List.of());

    assertSame(refused, assertThrows(IllegalStateException.class, stream::hasNext));
    assertSame(
        fatal,
        assertThrows(
            OutOfMemoryError.class,
            answering(
                        () -> {
                          throw fatal;
                        })
                    .chatStream(List.of(), List.of())
                ::hasNext));
  }

  @Test
  void aCancellationTheCallThrowsIsThrownAsItself() {
    var cancelled = new CancellationException("upstream");
    var stream =
        answering(
                () -> {
                  Thread.currentThread().interrupt();
                  throw cancelled;
                })
            .chatStream(List.of(), List.of());
    try {
      assertSame(cancelled, assertThrows(CancellationException.class, stream::hasNext));
    } finally {
      assertTrue(Thread.interrupted());
    }
  }

  @Test
  void closingBeforeTheFirstHasNextMeansTheCallNeverRuns() {
    var stream = answering(() -> reply("never").build()).chatStream(List.of(), List.of());

    stream.close();

    assertThrows(CancellationException.class, stream::hasNext);
    assertEquals(0, calls.get());
  }

  @Test
  void closingDuringTheCallInterruptsItAndHasNextThrowsCancellation() {
    var running = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var stream =
        answering(
                () -> {
                  running.countDown();
                  try {
                    new CountDownLatch(1).await();
                  } catch (InterruptedException e) {
                    interrupted.countDown();
                  }
                  return reply("too late").build();
                })
            .chatStream(List.of(), List.of());
    try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
      var read = reader.submit(stream::hasNext);
      Await.latch("the call running", running);

      stream.close();

      Await.latch("the call interrupted", interrupted);
      assertInstanceOf(CancellationException.class, Await.failure("the read", read));
    }
  }

  @Test
  void aCallThatIgnoresTheInterruptHasItsAnswerDropped() {
    var running = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var stream =
        answering(
                () -> {
                  running.countDown();
                  Uninterruptible.await(release);
                  return reply("dropped").build();
                })
            .chatStream(List.of(), List.of());
    try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
      var read = reader.submit(stream::hasNext);
      Await.latch("the call running", running);

      stream.close();
      release.countDown();

      assertInstanceOf(CancellationException.class, Await.failure("the read", read));
    }
  }

  @Test
  void anInterruptOfTheReadingThreadDuringTheCallStaysSetWhenTheStreamIsAlsoClosed() {
    var running = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var readingThread = new AtomicReference<Thread>();
    var stream =
        answering(
                () -> {
                  readingThread.set(Thread.currentThread());
                  running.countDown();
                  Uninterruptible.await(interrupted);
                  Uninterruptible.await(release);
                  return reply("dropped").build();
                })
            .chatStream(List.of(), List.of());
    try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
      var read =
          reader.submit(
              () -> {
                assertThrows(CancellationException.class, stream::hasNext);
                return Thread.interrupted();
              });
      Await.latch("the call running", running);

      readingThread.get().interrupt();
      interrupted.countDown();
      stream.close();
      release.countDown();

      assertTrue(Await.value("the read", read), "the reading thread's interrupt");
    }
  }

  @Test
  void aReadingThreadInterruptedBeforeTheFirstHasNextNeverRunsTheCallAndStaysSet() {
    var stream =
        answering(
                () -> {
                  try {
                    new CountDownLatch(1).await();
                  } catch (InterruptedException e) {
                    return reply("swallowed the interrupt").build();
                  }
                  return reply("never").build();
                })
            .chatStream(List.of(), List.of());

    Thread.currentThread().interrupt();
    try {
      assertThrows(CancellationException.class, stream::hasNext);
    } finally {
      assertTrue(Thread.interrupted());
    }
    assertEquals(0, calls.get());
    assertFalse(stream.hasNext());
  }

  @Test
  void anInterruptOfTheReadingThreadDuringTheCallEndsTheStreamInCancellationAndStaysSet() {
    var refused = new IllegalStateException("interrupted call");
    var answered =
        answering(
            () -> {
              Thread.currentThread().interrupt();
              return reply("ignored the interrupt").build();
            });
    var failing =
        answering(
            () -> {
              Thread.currentThread().interrupt();
              throw refused;
            });

    try {
      assertThrows(CancellationException.class, answered.chatStream(List.of(), List.of())::hasNext);
    } finally {
      assertTrue(Thread.interrupted());
    }
    CancellationException cancelled;
    try {
      cancelled =
          assertThrows(
              CancellationException.class, failing.chatStream(List.of(), List.of())::hasNext);
    } finally {
      assertTrue(Thread.interrupted());
    }
    assertSame(refused, cancelled.getCause());
    assertEquals(2, calls.get());
  }

  @Test
  void closingBetweenTheChunksOfTheAnswerEndsTheStreamInCancellation() {
    var stream = answering(() -> reply("hi").build()).chatStream(List.of(), List.of());
    assertEquals(new ModelChunk.TextDelta("hi"), stream.next());

    stream.close();

    assertThrows(CancellationException.class, stream::hasNext);
    assertFalse(stream.hasNext());
    assertEquals(1, calls.get());
  }

  @Test
  void anInterruptBetweenTheChunksOfTheAnswerEndsTheStreamInCancellationAndStaysSet() {
    var stream = answering(() -> reply("hi").build()).chatStream(List.of(), List.of());
    assertEquals(new ModelChunk.TextDelta("hi"), stream.next());

    Thread.currentThread().interrupt();
    try {
      assertThrows(CancellationException.class, stream::hasNext);
    } finally {
      assertTrue(Thread.interrupted());
    }
    assertFalse(stream.hasNext());
  }

  @Test
  void aStreamThatEndedIsNotCancelledByALaterClose() {
    var stream = answering(() -> reply("hi").build()).chatStream(List.of(), List.of());
    stream.next();
    stream.next();

    stream.close();

    assertFalse(stream.hasNext());
  }

  @Test
  void aFailedCallIsNeverRunAgainByAnotherRead() {
    var refused = new IllegalStateException("refused");
    var fatal = new OutOfMemoryError("host");

    assertReadOnceFailing(
        refused,
        () -> {
          throw refused;
        });
    assertReadOnceFailing(
        fatal,
        () -> {
          throw fatal;
        });
  }

  @Test
  void nextAfterTheStopThrows() {
    var stream = answering(() -> reply("hi").build()).chatStream(List.of(), List.of());
    stream.next();
    stream.next();

    assertFalse(stream.hasNext());
    assertThrows(NoSuchElementException.class, stream::next);
  }

  @Test
  void aNullCallIsRejected() {
    assertThrows(NullPointerException.class, () -> BlockingModel.streamOf(null));
  }

  private void assertReadOnceFailing(Throwable failure, Supplier<Response<Void>> call) {
    calls.set(0);
    var stream = answering(call).chatStream(List.of(), List.of());

    assertSame(failure, assertThrows(Throwable.class, stream::hasNext));

    assertFalse(stream.hasNext());
    assertThrows(NoSuchElementException.class, stream::next);
    assertEquals(1, calls.get());
  }

  private BlockingModel answering(Supplier<Response<Void>> answer) {
    return new BlockingModel() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        calls.incrementAndGet();
        return answer.get();
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

  private static Response.Builder<Void> reply(String content) {
    return Response.newBuilder()
        .withContent(content)
        .withFinishReason(FinishReason.STOP)
        .withUsage(USAGE);
  }

  private static List<ModelChunk> drain(Model model) {
    return StreamRead.of(model.chatStream(List.of(), List.of())).items();
  }
}
