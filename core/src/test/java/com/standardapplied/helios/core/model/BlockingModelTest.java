/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.RecordingSubscriber;
import com.standardapplied.helios.core.tool.Tool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class BlockingModelTest {

  record Answer(String answer) {}

  private static final Response.Usage USAGE = Response.Usage.of(5, 3);

  private final AtomicInteger calls = new AtomicInteger();

  @Test
  void theBlockingCallRunsOnTheFirstRequestNotBefore() {
    var model = answering(() -> reply("hi").build());
    var subscriber = new RecordingSubscriber<ModelChunk>(0);

    var publisher = model.chatStream(List.of(), List.of(), new CancellationToken());
    publisher.subscribe(subscriber);
    subscriber.subscription();
    var callsBeforeRequest = calls.get();
    subscriber.request(Long.MAX_VALUE);

    assertEquals(0, callsBeforeRequest);
    assertEquals(
        List.of(
            new ModelChunk.TextDelta("hi"), new ModelChunk.MessageStop("STOP", USAGE, Map.of())),
        subscriber.awaitCompletion());
    assertEquals(1, calls.get());
  }

  @Test
  void theResponseIsPublishedAsThinkingTextToolPairsThenTheStop() {
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
    var subscriber = new RecordingSubscriber<ModelChunk>();

    model
        .chatStream(List.of(), List.of(), OutputSchema.of(Answer.class), new CancellationToken())
        .subscribe(subscriber);

    assertEquals(
        new ModelChunk.TextDelta("{\"answer\":\"42\"}"), subscriber.awaitCompletion().getFirst());
    assertThrows(
        NullPointerException.class,
        () -> model.chatStream(List.of(), List.of(), null, new CancellationToken()));
  }

  @Test
  void aThrowingCallEndsTheStreamWithItsException() {
    var refused = new IllegalStateException("refused");
    var subscriber = new RecordingSubscriber<ModelChunk>();

    answering(
            () -> {
              throw refused;
            })
        .chatStream(List.of(), List.of(), new CancellationToken())
        .subscribe(subscriber);

    assertSame(refused, subscriber.awaitFailure());
  }

  @Test
  void cancellingTheTokenInterruptsTheBlockingCall() {
    var running = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var token = new CancellationToken();
    var subscriber = new RecordingSubscriber<ModelChunk>();

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
        .chatStream(List.of(), List.of(), token)
        .subscribe(subscriber);
    Await.latch("the call running", running);
    token.cancel("session closed");

    Await.latch("the call interrupted", interrupted);
    var failure = assertInstanceOf(CancellationException.class, subscriber.awaitFailure());
    assertEquals("session closed", failure.getMessage());
    assertEquals(List.of(), subscriber.items());
  }

  @Test
  void aNullCallIsRejected() {
    var token = new CancellationToken();

    assertThrows(NullPointerException.class, () -> BlockingModel.publisherOf(null, token));
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
    var subscriber = new RecordingSubscriber<ModelChunk>();
    model.chatStream(List.of(), List.of(), new CancellationToken()).subscribe(subscriber);
    return subscriber.awaitCompletion();
  }
}
