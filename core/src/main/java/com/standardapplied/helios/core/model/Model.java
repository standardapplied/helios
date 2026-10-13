/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.model;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.tool.Tool;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * Interface for LLM providers. Implementations provide the actual integration with model APIs
 * (Gemini, Anthropic, etc.). The one way to call a model is its chunk stream, {@link
 * #chatStream(List, List, CancellationToken)}; the blocking {@code chat} forms wait for it.
 *
 * <p>Models may hold long-lived resources (HTTP connection pools, file descriptors). Long-running
 * hosts that build and discard models should call {@link #close()} to release them. Implementations
 * must make {@code close()} idempotent. The default is a no-op for stateless implementations.
 */
public interface Model extends AutoCloseable {

  /**
   * Stream the model's answer to {@code messages} as a {@link Flow.Publisher} of normalized {@link
   * ModelChunk}s: text, thinking, tool-call fragments and completions as they arrive, then one
   * {@link ModelChunk.MessageStop} carrying the finish reason, usage, metadata and citations, then
   * {@code onComplete}. A failed turn ends in {@code onError} instead.
   *
   * <p>The contract every implementation keeps:
   *
   * <ul>
   *   <li><b>Lazy.</b> Calling this method does no provider I/O and runs no model work; the work
   *       starts on the subscriber's first request.
   *   <li><b>One subscriber, bounded by demand.</b> A second subscriber is refused with {@code
   *       onError}; no chunk is delivered beyond what was requested.
   *   <li><b>Cancellable.</b> Cancelling the subscription releases the request, its response body
   *       and any thread working for it, and ends the signals. Cancelling {@code cancellation} does
   *       the same and ends the stream with {@code onError} carrying a {@link
   *       java.util.concurrent.CancellationException} with the token's reason. Neither closes this
   *       model, which other callers may share.
   * </ul>
   *
   * <p>A model that can only answer blockingly implements {@link BlockingModel}, which runs the
   * blocking call as work the subscription owns, started on the first request.
   *
   * @param messages the conversation history
   * @param tools the tools the model may call
   * @param cancellation ends the stream once cancelled
   * @return a single-subscriber publisher of the turn's chunks
   */
  Flow.Publisher<ModelChunk> chatStream(
      List<Message> messages, List<Tool> tools, CancellationToken cancellation);

  /**
   * {@link #chatStream(List, List, CancellationToken)} with the answer constrained to {@code
   * outputSchema} through the provider's native structured-output channel. The chunks have the same
   * shape; a text-only turn whose content does not match the schema ends in {@code onError} with a
   * {@link com.standardapplied.helios.core.schema.StructuredOutputParseException} after its text
   * has streamed. A turn that calls tools is not checked: it is intermediate, and a later text-only
   * turn delivers the structured answer. The default throws, for a model without structured output.
   *
   * @param messages the conversation history
   * @param tools the tools the model may call
   * @param outputSchema the schema the answer must match
   * @param cancellation ends the stream once cancelled
   * @return a single-subscriber publisher of the turn's chunks
   * @throws UnsupportedOperationException if this model has no structured output
   */
  default Flow.Publisher<ModelChunk> chatStream(
      List<Message> messages,
      List<Tool> tools,
      OutputSchema<?> outputSchema,
      CancellationToken cancellation) {
    throw new UnsupportedOperationException("Structured output not supported by this model");
  }

  /**
   * The model's answer to {@code messages}, waited for. The default drains {@link #chatStream(List,
   * List, CancellationToken)}; an interrupt while waiting cancels the stream and throws a {@link
   * java.util.concurrent.CancellationException} with the interrupt restored.
   *
   * @param messages the conversation history
   * @param tools the tools the model may call
   * @return the model's response
   */
  default Response<Void> chat(List<Message> messages, List<Tool> tools) {
    return ChunkDrain.drain(chatStream(messages, tools, new CancellationToken()));
  }

  /**
   * The model's answer to {@code messages}, constrained to and parsed by {@code outputSchema}. The
   * default throws, for a model without structured output.
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
