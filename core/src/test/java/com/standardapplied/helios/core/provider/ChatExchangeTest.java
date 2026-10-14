/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.HeldSseServer;
import com.standardapplied.helios.core.test.RecordingSubscriber;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class ChatExchangeTest {

  private static final Response<Void> DONE =
      Response.newBuilder().withContent("ok").withFinishReason(FinishReason.STOP).build();

  private static final Response<Void> NOTHING =
      Response.newBuilder().withContent("").withFinishReason(FinishReason.ERROR).build();

  private final List<String> opened = new CopyOnWriteArrayList<>();

  @Test
  void theStreamOpensOnTheFirstRequestAndPublishesItsEventsThenClosesIt() {
    var events = List.<StreamEvent>of(new StreamEvent.TextDelta("ok"), new StreamEvent.Done(DONE));
    var stream = new Recorded(events);
    var subscriber = new RecordingSubscriber<StreamEvent>(0);

    exchange(request -> stream).stream("req", new CancellationToken()).subscribe(subscriber);
    subscriber.subscription();
    var openedBeforeRequest = List.copyOf(opened);
    subscriber.request(Long.MAX_VALUE);

    assertEquals(List.of(), openedBeforeRequest);
    assertEquals(events, subscriber.awaitCompletion());
    assertEquals(List.of("req"), opened);
    Await.latch("the stream closed", stream.closed);
  }

  @Test
  void theProvidersExceptionIsTheStreamsOneErrorEvent() {
    var own = new ProviderException("API error (status 400): bad", 400, null);

    var error =
        onlyError(
            request -> {
              throw own;
            });

    assertEquals(own.getMessage(), error.message());
    assertSame(own, error.cause());
    assertEquals(NOTHING, error.partial());
  }

  @Test
  void aFailureToCommunicateIsTheStreamsOneErrorEventNamingTheApi() {
    var refused = new IOException("refused");

    var error =
        onlyError(
            request -> {
              throw refused;
            });

    assertEquals("Failed to communicate with Acme API", error.message());
    assertSame(refused, error.cause());
  }

  @Test
  void anInterruptWhileSendingIsTheStreamsOneErrorEvent() {
    var interrupt = new InterruptedException();

    var error =
        onlyError(
            request -> {
              throw interrupt;
            });

    assertEquals("Request interrupted", error.message());
    assertSame(interrupt, error.cause());
  }

  @Test
  void cancellingTheTokenEndsTheStreamAndClosesIt() {
    var token = new CancellationToken();
    var stream = new Recorded(List.of());
    stream.blocks = true;
    var subscriber = new RecordingSubscriber<StreamEvent>();

    exchange(request -> stream).stream("req", token).subscribe(subscriber);
    Await.latch("the read blocked", stream.blocked);
    token.cancel("stop");

    assertInstanceOf(CancellationException.class, subscriber.awaitFailure());
    Await.latch("the stream closed", stream.closed);
  }

  @Test
  void cancellingTheSubscriptionWhileAwaitingHeadersClosesTheConnection() throws IOException {
    var reply = HeldSseServer.Reply.holdingHeaders("data: a\n\n");
    try (var client = HttpClientFactory.create();
        var server = HeldSseServer.start(reply)) {
      var subscriber = new RecordingSubscriber<StreamEvent>();
      overHttp(client, server).stream("{}", new CancellationToken()).subscribe(subscriber);
      reply.awaitRequest();

      subscriber.cancel();

      reply.awaitClientClosed();
    }
  }

  @Test
  void cancellingTheSubscriptionDuringAStalledBodyClosesTheConnection() throws IOException {
    var reply = HeldSseServer.Reply.of("data: a\n\n", "data: b\n\n");
    try (var client = HttpClientFactory.create();
        var server = HeldSseServer.start(reply)) {
      var subscriber = new RecordingSubscriber<StreamEvent>();
      overHttp(client, server).stream("{}", new CancellationToken()).subscribe(subscriber);
      assertEquals(new StreamEvent.TextDelta("a"), subscriber.next());

      subscriber.cancel();

      reply.awaitClientClosed();
    }
  }

  @Test
  void nullPartsAreRejected() {
    assertThrows(NullPointerException.class, () -> new ChatExchange<String>(null, r -> null));
    assertThrows(NullPointerException.class, () -> new ChatExchange<String>("Acme API", null));
  }

  private StreamEvent.Error onlyError(ChatExchange.StreamOpener<String> opener) {
    var subscriber = new RecordingSubscriber<StreamEvent>();
    exchange(opener).stream("req", new CancellationToken()).subscribe(subscriber);
    var events = subscriber.awaitCompletion();
    assertEquals(1, events.size());
    return assertInstanceOf(StreamEvent.Error.class, events.getFirst());
  }

  /** An exchange posting its request to {@code server}, each event payload read as text. */
  private static ChatExchange<String> overHttp(HttpClient client, HeldSseServer server) {
    return new ChatExchange<>(
        "Acme API",
        body ->
            SseReader.open(
                client,
                HttpRequest.newBuilder(server.uri())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                Duration.ofHours(1),
                new SseReader.Parser() {
                  @Override
                  public StreamEvent parse(String payload) {
                    return new StreamEvent.TextDelta(payload);
                  }

                  @Override
                  public Response<Void> partial() {
                    return NOTHING;
                  }
                },
                (message, status, cause) -> new ProviderException(message, status, cause)));
  }

  private ChatExchange<String> exchange(ChatExchange.StreamOpener<String> opener) {
    return new ChatExchange<>(
        "Acme API",
        request -> {
          opened.add(request);
          return opener.open(request);
        });
  }

  /** Recorded events, optionally blocking the read after the last one until closed. */
  static final class Recorded implements CloseableIterator<StreamEvent> {
    private final List<StreamEvent> events;
    final CountDownLatch closed = new CountDownLatch(1);
    final CountDownLatch blocked = new CountDownLatch(1);
    private int next;
    boolean blocks;

    Recorded(List<StreamEvent> events) {
      this.events = events;
    }

    @Override
    public boolean hasNext() {
      if (blocks && next == events.size()) {
        blocked.countDown();
        try {
          closed.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return next < events.size();
    }

    @Override
    public StreamEvent next() {
      return events.get(next++);
    }

    @Override
    public void close() {
      closed.countDown();
    }
  }
}
