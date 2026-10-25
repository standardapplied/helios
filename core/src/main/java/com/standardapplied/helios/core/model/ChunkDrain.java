/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The response a chunk stream adds up to, the one drain behind every blocking {@code chat}: text
 * and thinking deltas joined, completed tool calls in order, and the finish reason, usage, metadata
 * and citations of the {@link ModelChunk.MessageStop}.
 */
public final class ChunkDrain {

  private ChunkDrain() {}

  /**
   * Reads {@code stream} to its end on the calling thread. The caller closes it.
   *
   * @param stream the chunks of one turn
   * @return the response the chunks add up to
   * @throws IllegalStateException if the stream ends without its {@code MessageStop}
   * @throws RuntimeException what the stream's {@code hasNext()} threw, as itself
   * @throws Error what the stream's {@code hasNext()} threw, as itself
   */
  public static Response<Void> drain(CloseableIterator<ModelChunk> stream) {
    var content = new StringBuilder();
    var thinking = new StringBuilder();
    var toolCalls = new ArrayList<ToolCall>();
    ModelChunk.MessageStop stop = null;
    while (stream.hasNext()) {
      switch (stream.next()) {
        case ModelChunk.TextDelta(var text) -> content.append(text);
        case ModelChunk.ThinkingDelta(var text) -> thinking.append(text);
        case ModelChunk.ToolUseStop(var call) -> toolCalls.add(call);
        case ModelChunk.MessageStop messageStop -> stop = messageStop;
        case ModelChunk.ToolUseStart ignored -> {}
        case ModelChunk.ToolUseDelta ignored -> {}
        case ModelChunk.UsageDelta ignored -> {}
        case ModelChunk.KeepAlive ignored -> {}
      }
    }
    if (stop == null) {
      throw new IllegalStateException("the model's stream ended without its MessageStop");
    }
    return response(content, thinking, toolCalls, stop);
  }

  private static Response<Void> response(
      StringBuilder content,
      StringBuilder thinking,
      List<ToolCall> toolCalls,
      ModelChunk.MessageStop stop) {
    return Response.newBuilder()
        .withContent(content.toString())
        .withThinking(thinking.isEmpty() ? null : thinking.toString())
        .withToolCalls(toolCalls)
        .withFinishReason(stop.finishReason())
        .withUsage(stop.usage())
        .withMetadata(stop.metadata())
        .withCitations(stop.citations())
        .build();
  }
}
