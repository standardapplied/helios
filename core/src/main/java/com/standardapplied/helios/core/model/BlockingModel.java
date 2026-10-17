/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.model;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.tool.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

/**
 * A {@link Model} that can only answer blockingly. It implements {@code chat}; its chunk stream
 * runs that call as work the subscription owns, on its own thread, started by the subscriber's
 * first request and never before. The finished response is published whole: thinking, text, a
 * start/stop pair per tool call, then the message stop. Nothing is published before the call
 * returns, so a session's idle clock, which starts at the first chunk, does not run during the
 * call; its {@code maxWallClock} and cancellation bound it. Cancelling the subscription or the
 * token interrupts that thread; a call that ignores the interrupt runs until it returns and its
 * answer is dropped, since Java cannot end a thread by force, while an {@link Error} it throws
 * still reaches the subscriber through {@code onError}.
 */
public interface BlockingModel extends Model {

  /**
   * The model's answer to {@code messages}, computed on the calling thread.
   *
   * @param messages the conversation history
   * @param tools the tools the model may call
   * @return the model's response
   */
  @Override
  Response<Void> chat(List<Message> messages, List<Tool> tools);

  /** Runs {@link #chat(List, List)} as the subscription's own work. */
  @Override
  default Flow.Publisher<ModelChunk> chatStream(
      List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
    return publisherOf(() -> chat(messages, tools), cancellation);
  }

  /** Runs {@link #chat(List, List, OutputSchema)} as the subscription's own work. */
  @Override
  default Flow.Publisher<ModelChunk> chatStream(
      List<Message> messages,
      List<Tool> tools,
      OutputSchema<?> outputSchema,
      CancellationToken cancellation) {
    Objects.requireNonNull(outputSchema, "outputSchema must not be null");
    return publisherOf(() -> chat(messages, tools, outputSchema), cancellation);
  }

  /**
   * The chunk stream of a blocking {@code call}, run on the subscription's own thread on the first
   * request: thinking, text, a start/stop pair per tool call, then the message stop. A call that
   * throws ends the stream with its exception.
   *
   * @param call the blocking call
   * @param cancellation ends the stream once cancelled
   * @return a single-subscriber publisher of the call's chunks
   */
  static Flow.Publisher<ModelChunk> publisherOf(
      Supplier<? extends Response<?>> call, CancellationToken cancellation) {
    Objects.requireNonNull(call, "call must not be null");
    return new IteratorPublisher<>(
        () -> CloseableIterator.of(chunksOf(call.get()).iterator()), cancellation);
  }

  private static List<ModelChunk> chunksOf(Response<?> response) {
    var chunks = new ArrayList<ModelChunk>();
    if (response.hasThinking()) {
      chunks.add(new ModelChunk.ThinkingDelta(response.thinking()));
    }
    if (response.content() != null && !response.content().isEmpty()) {
      chunks.add(new ModelChunk.TextDelta(response.content()));
    }
    for (var call : response.toolCalls()) {
      chunks.add(new ModelChunk.ToolUseStart(call.id(), call.name()));
      chunks.add(new ModelChunk.ToolUseStop(call));
    }
    chunks.add(ModelChunk.MessageStop.of(response));
    return chunks;
  }
}
