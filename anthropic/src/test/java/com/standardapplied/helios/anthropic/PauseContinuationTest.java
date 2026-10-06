/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.anthropic.api.MessagesRequest;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.ChatExchange;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PauseContinuationTest {

  private static final String PAUSED_CONTENT = "[{\"type\":\"server_tool_use\",\"id\":\"s1\"}]";

  private final MessagesRequest request =
      new AnthropicRequestBuilder(
              AnthropicModelId.CLAUDE_OPUS_5_5.id(),
              AnthropicModelId.CLAUDE_OPUS_5_5,
              ModelConfig.newBuilder().withApiKey("k").build(),
              CachePolicy.shortLived())
          .build(List.of(Message.user("go")), List.of(), null);

  @Test
  void aPauseWithoutVerbatimContentCannotResume() {
    var paused = paused(Map.of(AnthropicResponseAssembler.STOP_REASON_KEY, "pause_turn"));

    var failure =
        assertThrows(
            AnthropicException.class, () -> continuation(r -> stream(paused)).chat(request));

    assertEquals(
        "pause_turn received without capturable assistant content; cannot resume",
        failure.getMessage());
  }

  @Test
  void aPauseWithEmptyVerbatimContentCannotResume() {
    var paused =
        paused(
            Map.of(
                AnthropicResponseAssembler.STOP_REASON_KEY,
                "pause_turn",
                RawContentEcho.RAW_CONTENT_KEY,
                ""));

    assertThrows(AnthropicException.class, () -> continuation(r -> stream(paused)).chat(request));
  }

  @Test
  void aPauseWithUnreadableVerbatimContentCannotResume() {
    var paused =
        paused(
            Map.of(
                AnthropicResponseAssembler.STOP_REASON_KEY, "pause_turn",
                RawContentEcho.RAW_CONTENT_KEY, "{not json"));

    var failure =
        assertThrows(
            AnthropicException.class, () -> continuation(r -> stream(paused)).chat(request));

    assertEquals("Failed to decode paused assistant content for resume", failure.getMessage());
  }

  @Test
  void aStreamThatKeepsPausingEndsWithAnErrorAfterTheLimit() throws Exception {
    var opened = new int[1];
    var events =
        continuation(
            r -> {
              opened[0]++;
              return stream(pausedWithContent());
            })
            .stream(request);

    var error = assertInstanceOf(StreamEvent.Error.class, events.next());

    assertEquals("pause_turn continuation limit exceeded after 8 resumes", error.message());
    assertNull(error.cause());
    assertEquals(PauseContinuation.MAX_PAUSE_CONTINUATIONS + 1, opened[0]);
  }

  @Test
  void aContinuationTheApiRefusesEndsTheStreamWithItsError() throws Exception {
    var refused = new AnthropicException("API error (status 400): bad", 400);

    var error =
        resumeFailingWith(
            r -> {
              throw refused;
            });

    assertEquals(refused.getMessage(), error.message());
    assertSame(refused, error.cause());
  }

  @Test
  void aContinuationThatCannotConnectEndsTheStream() throws Exception {
    var reset = new IOException("reset");

    var error =
        resumeFailingWith(
            r -> {
              throw reset;
            });

    assertEquals("Failed to reopen paused stream", error.message());
    assertSame(reset, error.cause());
  }

  @Test
  void aContinuationInterruptedWhileOpeningEndsTheStreamAndKeepsTheInterrupt() throws Exception {
    var interrupt = new InterruptedException();

    var error =
        resumeFailingWith(
            r -> {
              throw interrupt;
            });

    assertTrue(Thread.interrupted());
    assertEquals("Request interrupted", error.message());
    assertSame(interrupt, error.cause());
  }

  @Test
  void aFirstSegmentThatCannotOpenIsTheStreamsOneError() {
    var refused = new AnthropicException("API error (status 400): bad", 400);

    var events =
        continuation(
            r -> {
              throw refused;
            })
            .stream(request);

    var error = assertInstanceOf(StreamEvent.Error.class, events.next());
    assertEquals(refused.getMessage(), error.message());
    assertSame(refused, error.cause());
    assertFalse(events.hasNext());
  }

  @Test
  void eventsBeforeThePauseStreamThrough() throws Exception {
    var text = new StreamEvent.TextDelta("searching");
    var done = new StreamEvent.Done(paused(Map.of()));
    var events = continuation(r -> new Events(List.of(text, done))).stream(request);

    assertTrue(events.hasNext());
    assertSame(text, events.next());
    assertInstanceOf(StreamEvent.Done.class, events.next());
    assertFalse(events.hasNext());
  }

  private StreamEvent.Error resumeFailingWith(ChatExchange.StreamOpener<MessagesRequest> resume)
      throws Exception {
    var opened = new int[1];
    var events =
        continuation(r -> opened[0]++ == 0 ? stream(pausedWithContent()) : resume.open(r)).stream(
            request);
    return assertInstanceOf(StreamEvent.Error.class, events.next());
  }

  static PauseContinuation continuation(ChatExchange.StreamOpener<MessagesRequest> segments) {
    var exchange =
        new ChatExchange<>(
            AnthropicProvider.PROVIDER_NAME, "Anthropic API", segments, AnthropicException::new);
    return new PauseContinuation(exchange, segments);
  }

  private static Response<Void> pausedWithContent() {
    return paused(
        Map.of(
            AnthropicResponseAssembler.STOP_REASON_KEY,
            "pause_turn",
            RawContentEcho.RAW_CONTENT_KEY,
            PAUSED_CONTENT));
  }

  private static Response<Void> paused(Map<String, String> metadata) {
    return Response.newBuilder()
        .withContent("")
        .withFinishReason(FinishReason.STOP)
        .withMetadata(metadata)
        .build();
  }

  private static CloseableIterator<StreamEvent> stream(Response<Void> response) {
    return new Events(List.of(new StreamEvent.Done(response)));
  }

  private static final class Events implements CloseableIterator<StreamEvent> {
    private final List<StreamEvent> events;
    private int next;

    Events(List<StreamEvent> events) {
      this.events = events;
    }

    @Override
    public boolean hasNext() {
      return next < events.size();
    }

    @Override
    public StreamEvent next() {
      return events.get(next++);
    }

    @Override
    public void close() {}
  }
}
