/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.tool.Tool;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
            new ModelChunk.KeepAlive(),
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
  void chatOnAStreamThatEndsWithoutItsStopThrows() {
    var model = streaming(new ModelChunk.TextDelta("hi"));

    var failure = assertThrows(IllegalStateException.class, () -> model.chat(List.of(), List.of()));

    assertEquals("the model's stream ended without its MessageStop", failure.getMessage());
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
  void chatClosesTheStreamItDrainedOrThatFailed() {
    var drained = new Tracked(List.<ModelChunk>of(stop()).iterator(), null);
    var failed = new Tracked(List.<ModelChunk>of().iterator(), new IllegalStateException("no"));

    model(drained).chat(List.of(), List.of());
    assertThrows(IllegalStateException.class, () -> model(failed).chat(List.of(), List.of()));

    assertTrue(drained.closed.get());
    assertTrue(failed.closed.get());
  }

  @Test
  void structuredOutputIsUnsupportedByDefault() {
    var model = streaming();
    var schema = OutputSchema.of(Answer.class);

    assertThrows(
        UnsupportedOperationException.class, () -> model.chat(List.of(), List.of(), schema));
    assertThrows(
        UnsupportedOperationException.class, () -> model.chatStream(List.of(), List.of(), schema));
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

  private static ModelChunk.MessageStop stop() {
    return new ModelChunk.MessageStop("STOP", Response.Usage.of(1, 1), Map.of());
  }

  private static void failing(RuntimeException failure) {
    model(new Tracked(List.<ModelChunk>of().iterator(), failure)).chat(List.of(), List.of());
  }

  private static void failing(Error failure) {
    model(
            new CloseableIterator<>() {
              @Override
              public boolean hasNext() {
                throw failure;
              }

              @Override
              public ModelChunk next() {
                throw failure;
              }

              @Override
              public void close() {}
            })
        .chat(List.of(), List.of());
  }

  private static Model streaming(ModelChunk... chunks) {
    return model(CloseableIterator.of(List.of(chunks).iterator()));
  }

  private static Model model(CloseableIterator<ModelChunk> stream) {
    return new Model() {
      @Override
      public CloseableIterator<ModelChunk> chatStream(List<Message> messages, List<Tool> tools) {
        return stream;
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

  /**
   * Yields {@code chunks}, then throws {@code failure} unless it is null, and records its close.
   */
  private record Tracked(
      Iterator<ModelChunk> chunks, RuntimeException failure, AtomicBoolean closed)
      implements CloseableIterator<ModelChunk> {

    Tracked(Iterator<ModelChunk> chunks, RuntimeException failure) {
      this(chunks, failure, new AtomicBoolean());
    }

    @Override
    public boolean hasNext() {
      if (!chunks.hasNext() && failure != null) {
        throw failure;
      }
      return chunks.hasNext();
    }

    @Override
    public ModelChunk next() {
      return chunks.next();
    }

    @Override
    public void close() {
      closed.set(true);
    }
  }
}
