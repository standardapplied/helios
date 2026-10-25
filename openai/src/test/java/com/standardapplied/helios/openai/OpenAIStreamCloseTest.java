/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import static com.standardapplied.helios.core.test.SseEvents.data;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.HeldSseServer;
import com.standardapplied.helios.core.test.HeldSseServer.Reply;
import com.standardapplied.helios.core.test.SseEvents;
import com.standardapplied.helios.core.test.StreamRead;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * An OpenAI {@code chatStream} against a server that holds its reply part by part: the request goes
 * out on the first read, text is read as it is written, and a close, an interrupt or an idle
 * timeout ends the read and drops the connection, wherever the reply is held.
 */
class OpenAIStreamCloseTest {

  private static final String HELLO =
      data("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"Hello\"}");

  private static final String COMPLETED =
      data(
          "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\","
              + "\"status\":\"completed\",\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}");

  /** Where the server holds the reply the stream under test reads. */
  enum HeldAt {
    HEADERS {
      @Override
      Reply reply() {
        return Reply.holdingHeaders(HELLO + COMPLETED);
      }

      @Override
      BlockedRead blockAtTheHold(Reply held, CloseableIterator<ModelChunk> stream) {
        var blocked = new BlockedRead(stream);
        held.awaitRequest();
        return blocked;
      }
    },
    BODY {
      @Override
      Reply reply() {
        return Reply.of(HELLO, COMPLETED);
      }

      @Override
      BlockedRead blockAtTheHold(Reply held, CloseableIterator<ModelChunk> stream) {
        assertEquals(
            "Hello",
            Await.value("the text before the hold", onItsOwnThread(() -> nextText(stream))));
        return new BlockedRead(stream);
      }
    };

    abstract Reply reply();

    /** Reads {@code stream} up to the hold, then starts a read that blocks there. */
    abstract BlockedRead blockAtTheHold(Reply held, CloseableIterator<ModelChunk> stream);
  }

  @Test
  void theFirstHasNextSendsTheOnlyRequest() throws Exception {
    try (var server = HeldSseServer.start(Reply.of(HELLO + COMPLETED));
        var openAi = openAi(server.uri() + "/v1/responses", SseEvents.NEVER_IDLE)) {
      var stream = ask(openAi);

      assertEquals(0, server.requests());
      assertTrue(Await.value("the first hasNext", onItsOwnThread(stream::hasNext)));
      assertEquals(1, server.requests());
      assertNull(Await.value("the rest", onItsOwnThread(() -> StreamRead.of(stream))).failure());
      assertEquals(1, server.requests());
    }
  }

  @Test
  void textIsReadWhileTheServerStillHoldsTheRestOfItsReply() throws Exception {
    var reply = Reply.of(HELLO, COMPLETED);
    try (var server = HeldSseServer.start(reply);
        var openAi = openAi(server.uri() + "/v1/responses", SseEvents.NEVER_IDLE)) {
      var stream = ask(openAi);
      assertEquals("Hello", Await.value("the first text", onItsOwnThread(() -> nextText(stream))));

      reply.release();

      var rest = Await.value("the rest", onItsOwnThread(() -> StreamRead.of(stream)));
      assertNull(rest.failure());
      assertInstanceOf(ModelChunk.MessageStop.class, rest.items().getLast());
    }
  }

  @ParameterizedTest
  @EnumSource(HeldAt.class)
  void aCloseFromAnotherThreadCancelsTheBlockedReadAndDropsTheConnection(HeldAt point)
      throws Exception {
    var held = point.reply();
    try (var server = HeldSseServer.start(held, Reply.of(HELLO + COMPLETED));
        var openAi = openAi(server.uri() + "/v1/responses", SseEvents.NEVER_IDLE)) {
      var stream = ask(openAi);
      var blocked = point.blockAtTheHold(held, stream);

      stream.close();

      held.awaitClientClosed();
      assertInstanceOf(CancellationException.class, blocked.outcome());
      assertTheModelStillServes(openAi);
    }
  }

  @ParameterizedTest
  @EnumSource(HeldAt.class)
  void anInterruptOfTheReadingThreadCancelsItsReadKeepsTheInterruptAndDropsTheConnection(
      HeldAt point) throws Exception {
    var held = point.reply();
    try (var server = HeldSseServer.start(held, Reply.of(HELLO + COMPLETED));
        var openAi = openAi(server.uri() + "/v1/responses", SseEvents.NEVER_IDLE)) {
      var stream = ask(openAi);
      var blocked = point.blockAtTheHold(held, stream);

      blocked.thread.interrupt();

      held.awaitClientClosed();
      assertInstanceOf(CancellationException.class, blocked.outcome());
      assertTrue(blocked.interruptKept.get(), "the reading thread's interrupt was cleared");
      assertTheModelStillServes(openAi);
    }
  }

  @Test
  void aReplyThatStallsAfterItsFirstPartFailsWithTheIdleTimeoutAndDropsTheConnection()
      throws Exception {
    var stalled = Reply.of(HELLO, COMPLETED);
    try (var server = HeldSseServer.start(stalled);
        var openAi = openAi(server.uri() + "/v1/responses", Duration.ofMillis(300))) {
      var stream = ask(openAi);

      var read = Await.value("the stalled read", onItsOwnThread(() -> StreamRead.of(stream)));

      var timeout = assertInstanceOf(OpenAIException.class, read.failure());
      assertTrue(timeout.getMessage().contains("Stream idle timeout"), timeout.getMessage());
      stalled.awaitClientClosed();
    }
  }

  @Test
  void aRefusedConnectionIsATransientFailureOfTheOpenAiApi() throws Exception {
    try (var openAi = openAi(refusingUrl(), SseEvents.NEVER_IDLE)) {
      var stream = ask(openAi);

      var refused = Await.value("the refused read", onItsOwnThread(() -> StreamRead.of(stream)));

      assertEquals(List.of(), refused.items());
      var failure = assertInstanceOf(TransientStreamException.class, refused.failure());
      assertInstanceOf(IOException.class, failure.getCause());
      assertEquals(OpenAIProvider.PROVIDER_NAME, failure.providerName());
      assertEquals("Failed to communicate with OpenAI API", failure.getMessage());
    }
  }

  /** The URL of a loopback port nothing listens on any more. */
  private static String refusingUrl() throws IOException {
    try (var listener = new ServerSocket()) {
      listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      return "http://127.0.0.1:" + listener.getLocalPort() + "/v1/responses";
    }
  }

  private static Model openAi(String url, Duration streamIdleTimeout) {
    return OpenAIFixture.createModel(
        OpenAIModelId.GPT_4O,
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withBaseUrl(url)
            .withResponseTimeout(Duration.ofMinutes(10))
            .withStreamIdleTimeout(streamIdleTimeout)
            .build());
  }

  private static CloseableIterator<ModelChunk> ask(Model openAi) {
    return openAi.chatStream(List.of(Message.user("Hi")), List.of());
  }

  private static <T> FutureTask<T> onItsOwnThread(Callable<T> read) {
    var task = new FutureTask<>(read);
    Thread.ofVirtual().start(task);
    return task;
  }

  private static String nextText(CloseableIterator<ModelChunk> stream) {
    var chunk = stream.next();
    return chunk instanceof ModelChunk.TextDelta(var text) ? text : nextText(stream);
  }

  private static List<ModelChunk> texts(StreamRead<ModelChunk> read) {
    return read.items().stream().filter(ModelChunk.TextDelta.class::isInstance).toList();
  }

  private static void assertTheModelStillServes(Model openAi) {
    var read = Await.value("another stream", onItsOwnThread(() -> StreamRead.of(ask(openAi))));

    assertNull(read.failure());
    assertEquals(List.of(new ModelChunk.TextDelta("Hello")), texts(read));
    assertInstanceOf(ModelChunk.MessageStop.class, read.items().getLast());
  }

  /** A read of the next text on a virtual thread of its own, started at once. */
  private static final class BlockedRead {

    private final AtomicBoolean interruptKept = new AtomicBoolean();
    private final FutureTask<String> read;
    private final Thread thread;

    BlockedRead(CloseableIterator<ModelChunk> stream) {
      read =
          new FutureTask<>(
              () -> {
                try {
                  return nextText(stream);
                } finally {
                  interruptKept.set(Thread.currentThread().isInterrupted());
                }
              });
      thread = Thread.ofVirtual().start(read);
    }

    Throwable outcome() {
      return Await.failure("the blocked read", read);
    }
  }
}
