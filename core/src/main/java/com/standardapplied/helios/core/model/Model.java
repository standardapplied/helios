/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.model;

import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.tool.Tool;
import java.util.List;

/**
 * Interface for LLM providers. Implementations provide the actual integration with model APIs
 * (Gemini, Anthropic, etc.). The one way to call a model is its chunk stream, {@link
 * #chatStream(List, List)}; the blocking {@code chat} drains it.
 *
 * <p>Models may hold long-lived resources (HTTP connection pools, file descriptors). Long-running
 * hosts that build and discard models should call {@link #close()} to release them. Implementations
 * must make {@code close()} idempotent. The default is a no-op for stateless implementations.
 */
public interface Model extends AutoCloseable {

  /**
   * Stream the model's answer to {@code messages} as normalized {@link ModelChunk}s, read with
   * {@code hasNext()} / {@code next()} by one reader in one pass: text, thinking, tool-call
   * fragments and completions as the provider streams them, ending with one {@link
   * ModelChunk.MessageStop} carrying the finish reason, usage, metadata and citations. A {@link
   * ModelChunk.KeepAlive} is published for each part of the provider's stream that carries nothing,
   * as proof of life only.
   *
   * <ul>
   *   <li><b>Lazy.</b> This method sends nothing and runs no model work; the work starts at the
   *       first {@code hasNext()}. What building the provider's request throws is thrown here.
   *   <li><b>Failures are thrown where the caller reads.</b> A failed turn throws its exception
   *       from {@code hasNext()}: the provider's exception, a {@link TransientStreamException}, or
   *       a {@link com.standardapplied.helios.core.schema.StructuredOutputParseException} after the
   *       text has streamed. A stream that ends without its {@code MessageStop} throws.
   *   <li><b>{@code close()} is the only cancel.</b> From any thread, at any time, idempotent and
   *       never throwing, it aborts the request, whether it waits for its headers or reads its
   *       body, releases any thread working for it, and never closes this model, which other
   *       callers may share. A {@code hasNext()} blocked on another thread then throws {@link
   *       java.util.concurrent.CancellationException}.
   *   <li><b>Interrupt.</b> An interrupt of the reading thread ends the stream as {@code close()}
   *       does: {@code hasNext()} throws {@code CancellationException} with the interrupt status
   *       kept, never a {@code TransientStreamException}, so nothing retries an interrupt.
   * </ul>
   *
   * <p>A model that can only answer blockingly implements {@link BlockingModel}.
   *
   * @param messages the conversation history
   * @param tools the tools the model may call
   * @return the turn's chunks, which the caller closes
   */
  CloseableIterator<ModelChunk> chatStream(List<Message> messages, List<Tool> tools);

  /**
   * {@link #chatStream(List, List)} with the answer constrained to {@code outputSchema} through the
   * provider's native structured-output channel. The chunks have the same shape; a text-only turn
   * whose content does not match the schema throws a {@link
   * com.standardapplied.helios.core.schema.StructuredOutputParseException} from {@code hasNext()}
   * after its text has streamed. A turn that calls tools is not checked: it is intermediate, and a
   * later text-only turn delivers the structured answer. The default throws, for a model without
   * structured output.
   *
   * @param messages the conversation history
   * @param tools the tools the model may call
   * @param outputSchema the schema the answer must match
   * @return the turn's chunks, which the caller closes
   * @throws UnsupportedOperationException if this model has no structured output
   */
  default CloseableIterator<ModelChunk> chatStream(
      List<Message> messages, List<Tool> tools, OutputSchema<?> outputSchema) {
    throw new UnsupportedOperationException("Structured output not supported by this model");
  }

  /**
   * The model's answer to {@code messages}, waited for. The default drains {@link #chatStream(List,
   * List)} with {@link ChunkDrain#drain}: what the stream throws is thrown as itself, and a stream
   * that ends without its {@link ModelChunk.MessageStop} throws {@link IllegalStateException}.
   *
   * @param messages the conversation history
   * @param tools the tools the model may call
   * @return the model's response
   */
  default Response<Void> chat(List<Message> messages, List<Tool> tools) {
    try (var stream = chatStream(messages, tools)) {
      return ChunkDrain.drain(stream);
    }
  }

  /**
   * The model's answer to {@code messages}, constrained to and parsed by {@code outputSchema}. The
   * default throws, for a model without structured output: unlike {@link #chat(List, List)} it
   * cannot drain the structured stream, since parsing the answer needs the model's own JSON
   * binding. A model that overrides the structured {@code chatStream} overrides this too.
   *
   * @param <T> the type of the structured output
   * @param messages the conversation history
   * @param tools the tools the model may call
   * @param outputSchema the schema for structured output
   * @return the response with its parsed value
   * @throws UnsupportedOperationException if this model has no structured output
   */
  default <T> Response<T> chat(
      List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
    throw new UnsupportedOperationException("Structured output not supported by this model");
  }

  /** The model identifier (e.g., "gemini-2.0-flash", "claude-3-opus"). */
  String id();

  /** The provider name (e.g., "gemini", "anthropic", "openai"). */
  String provider();

  /** Context window size in tokens. Returns 0 if unknown (compaction disabled). */
  default int contextWindow() {
    return 0;
  }

  /**
   * Maximum output tokens this model produces in a single response. Providers should return their
   * model's documented ceiling so callers that don't set {@link
   * ModelConfig.Builder#withMaxOutputTokens(Integer)} aren't silently truncated at a framework
   * default. Returns 0 if unknown — a {@code 0} fallback means the provider sends whatever the API
   * itself defaults to (or rejects with a 400 if the API requires the field).
   *
   * @return the per-model output ceiling, or {@code 0} when unknown
   */
  default int maxOutputTokens() {
    return 0;
  }

  /** Raw structured-output retention policy used by this model and its sessions. */
  default RawOutputCapturePolicy rawOutputCapturePolicy() {
    return RawOutputCapturePolicy.ENABLED;
  }

  /**
   * Release resources held by this model. Default no-op. Implementations holding HTTP clients,
   * connection pools, or other OS resources should override and clean up. Must be idempotent —
   * calling {@code close()} more than once must be safe and have no additional effect.
   *
   * <p>A {@code Model} is typically shared across many sessions (each session is per-request and
   * does not own the Model). The component that constructs the Model owns its lifecycle and is
   * responsible for calling {@code close()} once at application shutdown — sessions do not close
   * their Model on completion. Closing a Model while other sessions reference it will fail those
   * sessions' subsequent requests.
   */
  @Override
  default void close() {}
}
