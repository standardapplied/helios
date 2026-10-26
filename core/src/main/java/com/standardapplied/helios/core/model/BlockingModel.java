/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.model;

import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.tool.Tool;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A {@link Model} that can only answer blockingly: it implements {@code chat}, and its chunk stream
 * is {@link #streamOf} that call.
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

  /** Runs {@link #chat(List, List)} at the stream's first {@code hasNext()}. */
  @Override
  default CloseableIterator<ModelChunk> chatStream(List<Message> messages, List<Tool> tools) {
    return streamOf(() -> chat(messages, tools));
  }

  /** Runs {@link #chat(List, List, OutputSchema)} at the stream's first {@code hasNext()}. */
  @Override
  default CloseableIterator<ModelChunk> chatStream(
      List<Message> messages, List<Tool> tools, OutputSchema<?> outputSchema) {
    Objects.requireNonNull(outputSchema, "outputSchema must not be null");
    return streamOf(() -> chat(messages, tools, outputSchema));
  }

  /**
   * The chunk stream of a blocking {@code call}, run at the first {@code hasNext()} on the reading
   * thread: thinking, text, a start/stop pair per tool call, then the message stop. What the call
   * throws is thrown from {@code hasNext()} as itself. {@code close()} or an interrupt of the
   * reading thread before the call means it never runs; {@code close()} during it interrupts the
   * thread running it, and {@code hasNext()} throws {@link
   * java.util.concurrent.CancellationException} with that interrupt kept. A call that ignores the
   * interrupt runs until it returns and its answer is dropped, since Java cannot end a thread by
   * force.
   *
   * @param call the blocking call
   * @return the call's chunks
   * @throws NullPointerException if {@code call} is null
   */
  static CloseableIterator<ModelChunk> streamOf(Supplier<? extends Response<?>> call) {
    return new BlockingStream(Objects.requireNonNull(call, "call must not be null"));
  }
}
