/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static com.standardapplied.helios.core.test.SseEvents.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.HeldSseServer;
import com.standardapplied.helios.core.test.StreamRead;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * An Anthropic model's stream sends its request on the first {@code hasNext()}, and a close from
 * another thread or an interrupt of the reading thread aborts it, awaiting the headers or reading
 * the body: the connection is closed, the blocked read throws a {@link CancellationException},
 * never a retryable failure, and the model serves the next stream.
 */
class AnthropicStreamCloseTest {

  private static final List<Message> PROMPT = List.of(Message.user("hi"));

  private static final String HEAD =
      named(
              "message_start",
              "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
                  + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-4-6\","
                  + "\"stop_reason\":null,\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}")
          + named(
              "content_block_start",
              "{\"type\":\"content_block_start\",\"index\":0,"
                  + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
          + named(
              "content_block_delta",
              "{\"type\":\"content_block_delta\",\"index\":0,"
                  + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}");

  private static final String TAIL =
      named("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
          + named(
              "message_delta",
              "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                  + "\"usage\":{\"output_tokens\":2}}")
          + named("message_stop", "{\"type\":\"message_stop\"}");

  @Test
  void chatStreamSendsNothingBeforeTheFirstHasNext() throws IOException {
    try (var server = HeldSseServer.start(HeldSseServer.Reply.of(HEAD + TAIL));
        var model = model(server)) {
      var stream = model.chatStream(PROMPT, List.of());
      var sentBeforeRead = server.requests();

      assertServed(stream);

      assertEquals(0, sentBeforeRead);
      assertEquals(1, server.requests());
    }
  }

  @Test
  void closingWhileTheHeadersAreHeldAbortsTheRequestAndTheModelServesTheNextStream()
      throws IOException {
    var held = HeldSseServer.Reply.holdingHeaders(HEAD + TAIL);
    try (var server = HeldSseServer.start(held, HeldSseServer.Reply.of(HEAD + TAIL));
        var model = model(server)) {
      var reader = new Reader(model.chatStream(PROMPT, List.of()));
      held.awaitRequest();

      reader.stream.close();

      held.awaitClientClosed();
      assertInstanceOf(CancellationException.class, reader.awaitFailure());
      assertServed(model.chatStream(PROMPT, List.of()));
    }
  }

  @Test
  void closingMidBodyClosesTheConnectionAndTheModelServesTheNextStream() throws IOException {
    var held = HeldSseServer.Reply.of(HEAD, TAIL);
    try (var server = HeldSseServer.start(held, HeldSseServer.Reply.of(HEAD + TAIL));
        var model = model(server)) {
      var reader = new Reader(model.chatStream(PROMPT, List.of()));
      reader.awaitHello();

      reader.stream.close();

      held.awaitClientClosed();
      assertInstanceOf(CancellationException.class, reader.awaitFailure());
      assertServed(model.chatStream(PROMPT, List.of()));
    }
  }

  @Test
  void anInterruptWhileTheHeadersAreHeldAbortsTheRequestAndKeepsTheInterrupt() throws IOException {
    var held = HeldSseServer.Reply.holdingHeaders(HEAD + TAIL);
    try (var server = HeldSseServer.start(held, HeldSseServer.Reply.of(HEAD + TAIL));
        var model = model(server)) {
      var reader = new Reader(model.chatStream(PROMPT, List.of()));
      held.awaitRequest();

      reader.thread.interrupt();

      held.awaitClientClosed();
      assertInstanceOf(CancellationException.class, reader.awaitFailure());
      assertTrue(reader.interruptKept.get());
      assertServed(model.chatStream(PROMPT, List.of()));
    }
  }

  @Test
  void anInterruptMidBodyClosesTheConnectionAndKeepsTheInterrupt() throws IOException {
    var held = HeldSseServer.Reply.of(HEAD, TAIL);
    try (var server = HeldSseServer.start(held, HeldSseServer.Reply.of(HEAD + TAIL));
        var model = model(server)) {
      var reader = new Reader(model.chatStream(PROMPT, List.of()));
      reader.awaitHello();

      reader.thread.interrupt();

      held.awaitClientClosed();
      assertInstanceOf(CancellationException.class, reader.awaitFailure());
      assertTrue(reader.interruptKept.get());
      assertServed(model.chatStream(PROMPT, List.of()));
    }
  }

  private static Model model(HeldSseServer server) {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withBaseUrl(server.uri().toString())
            .build();
    return new AnthropicProvider().create(AnthropicModelId.CLAUDE_SONNET_4_6.id(), config);
  }

  private static void assertServed(CloseableIterator<ModelChunk> stream) {
    var read = StreamRead.of(stream);
    var content =
        read.items().stream().filter(chunk -> !(chunk instanceof ModelChunk.KeepAlive)).toList();

    assertNull(read.failure());
    assertEquals(new ModelChunk.TextDelta("Hello"), content.getFirst());
    assertEquals(
        "STOP", assertInstanceOf(ModelChunk.MessageStop.class, content.getLast()).stopReason());
    assertEquals(2, content.size());
  }

  /** Reads a stream on a thread of its own, handing each chunk over as it arrives. */
  private static final class Reader {
    private final CloseableIterator<ModelChunk> stream;
    private final LinkedBlockingQueue<ModelChunk> chunks = new LinkedBlockingQueue<>();
    private final AtomicBoolean interruptKept = new AtomicBoolean();
    private final FutureTask<Void> read;
    private final Thread thread;

    Reader(CloseableIterator<ModelChunk> stream) {
      this.stream = stream;
      this.read =
          new FutureTask<>(
              () -> {
                try {
                  while (stream.hasNext()) {
                    chunks.add(stream.next());
                  }
                  return null;
                } finally {
                  interruptKept.set(Thread.currentThread().isInterrupted());
                }
              });
      this.thread = Thread.ofVirtual().start(read);
    }

    void awaitHello() {
      var chunk = Await.next("the first chunk", chunks);
      while (chunk instanceof ModelChunk.KeepAlive) {
        chunk = Await.next("the next chunk", chunks);
      }
      assertEquals(new ModelChunk.TextDelta("Hello"), chunk);
    }

    Throwable awaitFailure() {
      return Await.failure("the blocked read", read);
    }
  }
}
