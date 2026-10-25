/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import static com.standardapplied.helios.gemini.GeminiSse.HELLO_DELTA;
import static com.standardapplied.helios.gemini.GeminiSse.INTERACTION_COMPLETED;
import static com.standardapplied.helios.gemini.GeminiSse.MODEL_OUTPUT_START;
import static com.standardapplied.helios.gemini.GeminiSse.MODEL_OUTPUT_STOP;
import static com.standardapplied.helios.gemini.GeminiSse.TEXT_FLOW;
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
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * A Gemini {@code chatStream} against a server that holds its reply: nothing is sent before the
 * first read, text arrives as the server writes it, and a close or an interrupt at the headers or
 * mid-body ends the read and the connection while the model serves the next stream.
 */
class GeminiStreamCloseTest {

  private static final String FIRST_PART = MODEL_OUTPUT_START + HELLO_DELTA;
  private static final String REST = MODEL_OUTPUT_STOP + INTERACTION_COMPLETED;

  @Test
  void nothingIsSentBeforeTheFirstHasNextAndTheFirstSendsOneRequest() throws Exception {
    try (var server = HeldSseServer.start(Reply.of(TEXT_FLOW));
        var gemini = gemini(server.uri(), SseEvents.NEVER_IDLE)) {
      var stream = ask(gemini);
      var sentBeforeRead = server.requests();

      var hasNext = Await.value("the first hasNext", started(stream::hasNext));

      assertTrue(hasNext);
      assertEquals(0, sentBeforeRead);
      assertEquals(1, server.requests());
      assertNull(Await.value("the stream", started(() -> StreamRead.of(stream))).failure());
      assertEquals(1, server.requests());
    }
  }

  @Test
  void theFirstTextIsReadBeforeTheServerReleasesTheRest() throws Exception {
    var reply = Reply.of(FIRST_PART, REST);
    try (var server = HeldSseServer.start(reply);
        var gemini = gemini(server.uri(), SseEvents.NEVER_IDLE)) {
      var stream = ask(gemini);

      assertEquals("Hello", Await.value("the first text", started(() -> text(stream))));

      reply.release();
      var rest = Await.value("the rest", started(() -> StreamRead.of(stream)));
      assertNull(rest.failure());
      assertInstanceOf(ModelChunk.MessageStop.class, rest.items().getLast());
    }
  }

  @Test
  void closingWhileTheServerHoldsTheHeadersCancelsTheBlockedReadAndClosesTheConnection()
      throws Exception {
    var held = Reply.holdingHeaders(TEXT_FLOW);
    try (var server = HeldSseServer.start(held, Reply.of(TEXT_FLOW));
        var gemini = gemini(server.uri(), SseEvents.NEVER_IDLE)) {
      var stream = ask(gemini);
      var blocked = started(stream::hasNext);
      held.awaitRequest();

      stream.close();

      held.awaitClientClosed();
      assertInstanceOf(CancellationException.class, Await.failure("the blocked read", blocked));
      assertServesAnotherStream(gemini);
    }
  }

  @Test
  void closingMidBodyCancelsTheBlockedReadAndClosesTheConnection() throws Exception {
    var held = Reply.of(FIRST_PART, REST);
    try (var server = HeldSseServer.start(held, Reply.of(TEXT_FLOW));
        var gemini = gemini(server.uri(), SseEvents.NEVER_IDLE)) {
      var stream = ask(gemini);
      Await.value("the first text", started(() -> text(stream)));
      var blocked = started(() -> text(stream));

      stream.close();

      held.awaitClientClosed();
      assertInstanceOf(CancellationException.class, Await.failure("the blocked read", blocked));
      assertServesAnotherStream(gemini);
    }
  }

  @Test
  void anInterruptWhileTheServerHoldsTheHeadersCancelsTheReadAndKeepsTheInterrupt()
      throws Exception {
    var held = Reply.holdingHeaders(TEXT_FLOW);
    try (var server = HeldSseServer.start(held, Reply.of(TEXT_FLOW));
        var gemini = gemini(server.uri(), SseEvents.NEVER_IDLE)) {
      var stream = ask(gemini);
      var interrupted = new InterruptedRead(stream::hasNext);
      held.awaitRequest();

      interrupted.interrupt();

      held.awaitClientClosed();
      interrupted.assertCancelledKeepingTheInterrupt();
      assertServesAnotherStream(gemini);
    }
  }

  @Test
  void anInterruptMidBodyCancelsTheReadAndKeepsTheInterrupt() throws Exception {
    var held = Reply.of(FIRST_PART, REST);
    var firstTextRead = new CountDownLatch(1);
    try (var server = HeldSseServer.start(held, Reply.of(TEXT_FLOW));
        var gemini = gemini(server.uri(), SseEvents.NEVER_IDLE)) {
      var stream = ask(gemini);
      var interrupted =
          new InterruptedRead(
              () -> {
                text(stream);
                firstTextRead.countDown();
                return text(stream);
              });
      Await.latch("the first text", firstTextRead);

      interrupted.interrupt();

      held.awaitClientClosed();
      interrupted.assertCancelledKeepingTheInterrupt();
      assertServesAnotherStream(gemini);
    }
  }

  @Test
  void aServerThatStopsWritingFailsTheReadWithAnIdleTimeoutAndIsDisconnected() throws Exception {
    var stalled = Reply.of(FIRST_PART, REST);
    try (var server = HeldSseServer.start(stalled);
        var gemini = gemini(server.uri(), Duration.ofMillis(300))) {
      var stream = ask(gemini);

      var read = Await.value("the stalled read", started(() -> StreamRead.of(stream)));

      var failure = assertInstanceOf(GeminiException.class, read.failure());
      assertTrue(failure.getMessage().contains("Stream idle timeout"), failure.getMessage());
      stalled.awaitClientClosed();
    }
  }

  @Test
  void anUnreachableServerFailsTheFirstReadAsATransientFailureNamingTheApi() throws Exception {
    URI unreachable;
    try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      unreachable = URI.create("http://127.0.0.1:" + socket.getLocalPort());
    }
    try (var gemini = gemini(unreachable, SseEvents.NEVER_IDLE)) {
      var stream = ask(gemini);

      var read = Await.value("the failed read", started(() -> StreamRead.of(stream)));

      var failure = assertInstanceOf(TransientStreamException.class, read.failure());
      assertEquals("Failed to communicate with Gemini API", failure.getMessage());
      assertEquals(GeminiProvider.PROVIDER_NAME, failure.providerName());
      assertInstanceOf(IOException.class, failure.getCause());
    }
  }

  private static Model gemini(URI server, Duration streamIdleTimeout) {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withBaseUrl(server.toString())
            .withResponseTimeout(Duration.ofMinutes(10))
            .withStreamIdleTimeout(streamIdleTimeout)
            .build();
    return new GeminiProvider().create(GeminiModelId.GEMINI_3_FLASH_PREVIEW.id(), config);
  }

  private static CloseableIterator<ModelChunk> ask(Model gemini) {
    return gemini.chatStream(List.of(Message.user("Hi")), List.of());
  }

  /** The text of the next text chunk {@code stream} yields. */
  private static String text(CloseableIterator<ModelChunk> stream) {
    while (true) {
      if (stream.next() instanceof ModelChunk.TextDelta(var text)) {
        return text;
      }
    }
  }

  private static void assertServesAnotherStream(Model gemini) {
    var read = Await.value("the next stream", started(() -> StreamRead.of(ask(gemini))));

    assertNull(read.failure());
    assertTrue(read.items().contains(new ModelChunk.TextDelta("Hello")));
    assertInstanceOf(ModelChunk.MessageStop.class, read.items().getLast());
  }

  private static <T> FutureTask<T> started(Callable<T> read) {
    var task = new FutureTask<>(read);
    Thread.ofVirtual().start(task);
    return task;
  }

  /** A read on a thread of its own, recording whether its interrupt stayed set. */
  private static final class InterruptedRead {

    private final AtomicBoolean interruptKept = new AtomicBoolean();
    private final FutureTask<Object> outcome;
    private final Thread thread;

    InterruptedRead(Callable<?> read) {
      outcome =
          new FutureTask<>(
              () -> {
                try {
                  return read.call();
                } finally {
                  interruptKept.set(Thread.currentThread().isInterrupted());
                }
              });
      thread = Thread.ofVirtual().start(outcome);
    }

    void interrupt() {
      thread.interrupt();
    }

    void assertCancelledKeepingTheInterrupt() {
      assertInstanceOf(CancellationException.class, Await.failure("the interrupted read", outcome));
      assertTrue(interruptKept.get(), "the interrupt was cleared");
    }
  }
}
