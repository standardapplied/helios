/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.test.HeldSseServer.Reply;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
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
 * A provider's {@code chatStream} against a server that holds its reply: nothing is sent before the
 * first {@code hasNext()}, text is read as the server writes it, a close from another thread or an
 * interrupt of the reading thread, at the headers or mid-body, drops the connection and throws
 * {@link CancellationException} with the interrupt kept while the model serves the next stream, an
 * idle server fails the read with the provider's idle timeout, and an unreachable one is a
 * transient failure naming the API. A provider's test extends this, so each provider runs these
 * cases.
 */
public abstract class StreamCloseContract {

  /** Where the server holds the reply when the read under test blocks. */
  enum Hold {
    HEADERS,
    BODY
  }

  /** The provider's model sending to {@code server}, failing a line that takes longer than idle. */
  protected abstract Model model(URI server, Duration streamIdleTimeout);

  /** The reply's events up to and including the delta of the text {@code "Hello"}. */
  protected abstract String hello();

  /** The rest of the reply, through the provider's terminal event. */
  protected abstract String rest();

  /** The API a failure to communicate names, e.g. {@code "Gemini API"}. */
  protected abstract String apiName();

  /** The provider name a transient failure carries. */
  protected abstract String providerName();

  @Test
  void nothingIsSentBeforeTheFirstHasNextAndTheFirstSendsTheOnlyRequest() throws Exception {
    try (var server = HeldSseServer.start(Reply.of(hello() + rest()));
        var model = model(server.uri(), SseEvents.NEVER_IDLE)) {
      var stream = ask(model);

      assertEquals(0, server.requests());
      assertTrue(Await.value("the first hasNext", onItsOwnThread(stream::hasNext)));
      assertEquals(1, server.requests());
      assertNull(Await.value("the rest", onItsOwnThread(() -> StreamRead.of(stream))).failure());
      assertEquals(1, server.requests());
    }
  }

  @Test
  void theFirstTextIsReadBeforeTheServerReleasesTheRest() throws Exception {
    var reply = Reply.of(hello(), rest());
    try (var server = HeldSseServer.start(reply);
        var model = model(server.uri(), SseEvents.NEVER_IDLE)) {
      var stream = ask(model);
      assertEquals("Hello", Await.value("the first text", onItsOwnThread(() -> nextText(stream))));

      reply.release();

      var rest = Await.value("the rest", onItsOwnThread(() -> StreamRead.of(stream)));
      assertNull(rest.failure());
      assertInstanceOf(ModelChunk.MessageStop.class, rest.items().getLast());
    }
  }

  @ParameterizedTest
  @EnumSource(Hold.class)
  void aCloseFromAnotherThreadCancelsTheBlockedReadAndDropsTheConnection(Hold hold)
      throws Exception {
    var held = reply(hold);
    try (var server = HeldSseServer.start(held, Reply.of(hello() + rest()));
        var model = model(server.uri(), SseEvents.NEVER_IDLE)) {
      var stream = ask(model);
      var blocked = blockAt(hold, held, stream);

      stream.close();

      held.awaitClientClosed();
      assertInstanceOf(CancellationException.class, blocked.failure());
      assertServesAnotherStream(model);
    }
  }

  @ParameterizedTest
  @EnumSource(Hold.class)
  void anInterruptOfTheReadingThreadCancelsItsReadKeepsTheInterruptAndDropsTheConnection(Hold hold)
      throws Exception {
    var held = reply(hold);
    try (var server = HeldSseServer.start(held, Reply.of(hello() + rest()));
        var model = model(server.uri(), SseEvents.NEVER_IDLE)) {
      var blocked = blockAt(hold, held, ask(model));

      blocked.thread.interrupt();

      held.awaitClientClosed();
      assertInstanceOf(CancellationException.class, blocked.failure());
      assertTrue(blocked.interruptKept.get(), "the reading thread's interrupt was cleared");
      assertServesAnotherStream(model);
    }
  }

  @Test
  void aServerThatStopsWritingFailsTheReadWithTheIdleTimeoutAndIsDisconnected() throws Exception {
    var stalled = Reply.of(hello(), rest());
    try (var server = HeldSseServer.start(stalled);
        var model = model(server.uri(), Duration.ofMillis(300))) {
      var stream = ask(model);

      var read = Await.value("the stalled read", onItsOwnThread(() -> StreamRead.of(stream)));

      var timeout = assertInstanceOf(ProviderException.class, read.failure());
      assertTrue(timeout.getMessage().contains("Stream idle timeout"), timeout.getMessage());
      stalled.awaitClientClosed();
    }
  }

  @Test
  void anUnreachableServerFailsTheFirstReadAsATransientFailureNamingTheApi() throws Exception {
    URI unreachable;
    try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      unreachable = URI.create("http://127.0.0.1:" + socket.getLocalPort());
    }
    try (var model = model(unreachable, SseEvents.NEVER_IDLE)) {
      var stream = ask(model);

      var read = Await.value("the failed read", onItsOwnThread(() -> StreamRead.of(stream)));

      assertEquals(List.of(), read.items());
      var failure = assertInstanceOf(TransientStreamException.class, read.failure());
      assertEquals("Failed to communicate with " + apiName(), failure.getMessage());
      assertEquals(providerName(), failure.providerName());
      assertInstanceOf(IOException.class, failure.getCause());
    }
  }

  private Reply reply(Hold hold) {
    return hold == Hold.HEADERS
        ? Reply.holdingHeaders(hello() + rest())
        : Reply.of(hello(), rest());
  }

  /** Reads {@code stream} up to the hold, then starts a read that blocks there. */
  private static BlockedRead blockAt(Hold hold, Reply held, CloseableIterator<ModelChunk> stream) {
    if (hold == Hold.HEADERS) {
      var blocked = new BlockedRead(stream);
      held.awaitRequest();
      return blocked;
    }
    assertEquals("Hello", Await.value("the first text", onItsOwnThread(() -> nextText(stream))));
    var blocked = new BlockedRead(stream);
    Await.until(
        "the read to wait for the next line",
        () -> blocked.thread.getState() == Thread.State.TIMED_WAITING);
    return blocked;
  }

  private void assertServesAnotherStream(Model model) {
    var read = Await.value("another stream", onItsOwnThread(() -> StreamRead.of(ask(model))));

    assertNull(read.failure());
    assertEquals(
        List.of(new ModelChunk.TextDelta("Hello")),
        read.items().stream().filter(ModelChunk.TextDelta.class::isInstance).toList());
    assertInstanceOf(ModelChunk.MessageStop.class, read.items().getLast());
  }

  private static CloseableIterator<ModelChunk> ask(Model model) {
    return model.chatStream(List.of(Message.user("Hi")), List.of());
  }

  private static String nextText(CloseableIterator<ModelChunk> stream) {
    var chunk = stream.next();
    return chunk instanceof ModelChunk.TextDelta(var text) ? text : nextText(stream);
  }

  private static <T> FutureTask<T> onItsOwnThread(Callable<T> read) {
    var task = new FutureTask<>(read);
    Thread.ofVirtual().start(task);
    return task;
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

    Throwable failure() {
      return Await.failure("the blocked read", read);
    }
  }
}
