/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Proves that a provider's chunk stream and its blocking {@code chat} tell the same story for one
 * recorded stream: the text deltas add up to the content, the tool-use stops are the tool calls in
 * order, each call's start precedes its deltas and its stop, the thinking deltas join into the
 * thinking by the provider's {@link ThinkingJoin}, and the message stop carries the response's
 * finish reason, usage, metadata and citations. A failed turn fails both ways with the same
 * exception.
 */
public final class StreamParity {

  /**
   * How a provider's response joins the thinking its deltas stream.
   *
   * @param separator the text the response puts between two joined pieces
   * @param everyDelta whether the separator stands between every two deltas, rather than only
   *     between two thinking blocks, which the chunk stream does not mark
   */
  public record ThinkingJoin(String separator, boolean everyDelta) {

    /** The separator stands between thinking blocks; the deltas of one block join directly. */
    public static ThinkingJoin betweenBlocks(String separator) {
      return new ThinkingJoin(separator, false);
    }

    /** The separator stands between every two deltas. */
    public static ThinkingJoin betweenDeltas(String separator) {
      return new ThinkingJoin(separator, true);
    }

    boolean joins(List<String> deltas, String thinking) {
      if (everyDelta) {
        return String.join(separator, deltas).equals(thinking);
      }
      return joinsFrom(deltas, 0, thinking, 0);
    }

    private boolean joinsFrom(List<String> deltas, int next, String thinking, int at) {
      if (next == deltas.size()) {
        return at == thinking.length();
      }
      var delta = deltas.get(next);
      if (thinking.startsWith(delta, at)
          && joinsFrom(deltas, next + 1, thinking, at + delta.length())) {
        return true;
      }
      var separated = separator + delta;
      return next > 0
          && thinking.startsWith(separated, at)
          && joinsFrom(deltas, next + 1, thinking, at + separated.length());
    }
  }

  private StreamParity() {}

  /**
   * Replays {@code fixture} once to {@code chatStream} and once to {@code chat} and asserts that
   * they agree.
   */
  public static void assertAgrees(String fixture, Function<URI, Model> modelAt, ThinkingJoin join) {
    var replies = ModelHarness.replies(fixture);
    var recorder = new RecordingSubscriber<ModelChunk>();
    var streamFailure = new AtomicReference<Throwable>();
    ModelHarness.exchange(
        replies,
        modelAt,
        model -> {
          model
              .chatStream(ModelHarness.PROMPT, List.of(), new CancellationToken())
              .subscribe(recorder);
          streamFailure.set(recorder.awaitEnd());
        });
    var response = new AtomicReference<Response<Void>>();
    var chatFailure = new AtomicReference<RuntimeException>();
    ModelHarness.exchange(
        replies,
        modelAt,
        model -> {
          try {
            response.set(model.chat(ModelHarness.PROMPT, List.of()));
          } catch (RuntimeException failure) {
            chatFailure.set(failure);
          }
        });
    var chunks = recorder.items();
    assertEquals(0, recorder.violations(), "publisher protocol violations");
    assertToolUseOrder(chunks);
    if (chatFailure.get() != null) {
      var render = new Transcript();
      assertNotNull(streamFailure.get(), "chat threw but the chunk stream completed");
      assertEquals(render.render(chatFailure.get()), render.render(streamFailure.get()));
      assertFalse(chunks.stream().anyMatch(ModelChunk.MessageStop.class::isInstance));
      return;
    }
    assertNull(streamFailure.get(), "chat returned but the chunk stream failed");
    assertAgreesWith(response.get(), chunks, join);
  }

  private static void assertAgreesWith(
      Response<Void> response, List<ModelChunk> chunks, ThinkingJoin join) {
    assertFalse(chunks.isEmpty(), "chat returned but the chunk stream published nothing");
    var text = new StringBuilder();
    var thinking = new ArrayList<String>();
    var calls = new ArrayList<ToolCall>();
    for (var chunk : chunks.subList(0, chunks.size() - 1)) {
      switch (chunk) {
        case ModelChunk.TextDelta(var delta) -> text.append(delta);
        case ModelChunk.ThinkingDelta(var delta) -> thinking.add(delta);
        case ModelChunk.ToolUseStop(var call) -> calls.add(call);
        case ModelChunk.MessageStop stop -> throw new AssertionError("a second " + stop);
        default -> {}
      }
    }
    assertEquals(response.content(), text.toString(), "content");
    assertEquals(response.toolCalls(), calls, "tool calls");
    assertEquals(ModelChunk.MessageStop.of(response), chunks.getLast(), "message stop");
    if (response.thinking() == null) {
      assertEquals(List.of(), thinking, "thinking deltas of a response without thinking");
    } else {
      assertTrue(
          join.joins(thinking, response.thinking()),
          () -> "thinking deltas " + thinking + " do not join into " + response.thinking());
    }
  }

  private static void assertToolUseOrder(List<ModelChunk> chunks) {
    var open = new HashSet<String>();
    for (var chunk : chunks) {
      switch (chunk) {
        case ModelChunk.ToolUseStart(var callId, var toolName) ->
            assertTrue(open.add(callId), "a second ToolUseStart for " + callId);
        case ModelChunk.ToolUseDelta(var callId, var argumentsDelta) ->
            assertTrue(open.contains(callId), "ToolUseDelta before ToolUseStart for " + callId);
        case ModelChunk.ToolUseStop(var call) ->
            assertTrue(open.remove(call.id()), "ToolUseStop without ToolUseStart for " + call.id());
        default -> {}
      }
    }
  }
}
