/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interface for LLM providers. Implementations provide the actual integration with model APIs
 * (Gemini, Anthropic, etc.).
 *
 * <p>Models may hold long-lived resources (HTTP connection pools, file descriptors). Long-running
 * hosts that build and discard models should call {@link #close()} to release them. Implementations
 * must make {@code close()} idempotent. The default is a no-op for stateless implementations.
 */
public interface Model extends AutoCloseable {

  /**
   * Send messages to the model and get a response.
   *
   * @param messages the conversation history
   * @param tools available tools the model can call
   * @return the model's response
   */
  Response<Void> chat(List<Message> messages, List<Tool> tools);

  /** Send messages without tools. */
  default Response<Void> chat(List<Message> messages) {
    return chat(messages, List.of());
  }

  /**
   * Send messages with structured output schema.
   *
   * @param <T> the type of the structured output
   * @param messages the conversation history
   * @param tools available tools the model can call
   * @param outputSchema the schema for structured output
   * @return response with parsed structured output
   */
  default <T> Response<T> chat(
      List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
    throw new UnsupportedOperationException("Structured output not supported by this model");
  }

  /**
   * Send messages with structured output schema, no tools.
   *
   * @param <T> the type of the structured output
   * @param messages the conversation history
   * @param outputSchema the schema for structured output
   * @return response with parsed structured output
   */
  default <T> Response<T> chat(List<Message> messages, OutputSchema<T> outputSchema) {
    return chat(messages, List.of(), outputSchema);
  }

  /**
   * Stream response from the model. The returned iterator may hold resources (HTTP connections,
   * streams) and should be used in a try-with-resources block to ensure cleanup.
   *
   * @param messages the conversation history
   * @param tools available tools
   * @return closeable iterator of stream events
   */
  default CloseableIterator<StreamEvent> chatStream(List<Message> messages, List<Tool> tools) {
    var response = chat(messages, tools);
    return CloseableIterator.of(List.of((StreamEvent) new StreamEvent.Done(response)).iterator());
  }

  /** Stream response without tools. */
  default CloseableIterator<StreamEvent> chatStream(List<Message> messages) {
    return chatStream(messages, List.of());
  }

  /**
   * Stream a response as a {@link Flow.Publisher} of normalized {@link ModelChunk} chunks. This is
   * the streaming entrypoint consumed by the session loop; the iterator-based {@code chatStream}
   * overload remains available for non-session callers.
   *
   * <p>Default implementation invokes the blocking {@link #chat(List, List)} and synthesises a
   * chunk sequence from the resulting {@link Response} so providers can opt into real streaming one
   * at a time. The synthesised sequence is: {@link ModelChunk.TextDelta} (if the response has
   * content), one {@link ModelChunk.ToolUseStart}/{@link ModelChunk.ToolUseStop} pair per tool call
   * in the order returned, and a final {@link ModelChunk.MessageStop} carrying the response's stop
   * reason and usage. {@link ModelChunk.ToolUseDelta} is not emitted by the default; real streaming
   * providers emit it when wire-level argument deltas arrive.
   *
   * <p>The returned publisher honors {@link CancellationToken}: when the subscriber first requests
   * a chunk, if the token is already cancelled the subscriber receives {@code onError} with a
   * {@link java.util.concurrent.CancellationException} carrying the cancellation reason. The
   * default implementation evaluates the blocking call eagerly before any subscription is opened,
   * so cancellation cannot interrupt the underlying {@code chat} call — that protection requires
   * the provider to override. Subscribers can also unsubscribe via {@link
   * Flow.Subscription#cancel()} to stop further chunk delivery.
   *
   * @param messages the conversation history
   * @param tools available tools the model can call
   * @param cancellation cooperative cancellation token; signal flowed through to {@code onError}
   * @return a single-subscriber publisher of the normalised chunk sequence
   * @throws NullPointerException if {@code cancellation} is null
   */
  default Flow.Publisher<ModelChunk> chatStream(
      List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    var response = chat(messages, tools);
    return subscriber -> deliverDefaultChunkSequence(response, cancellation, subscriber);
  }

  /**
   * Streaming chat with a structured-output schema. The schema is transmitted to the model via the
   * provider's native structured-output channel (Gemini {@code response_format.schema}, OpenAI
   * {@code text.format=json_schema}, Anthropic {@code system_instruction} text). The streamed
   * chunks remain the same {@link ModelChunk} shape as the untyped variant — tool calls and
   * structured-text responses both arrive as the usual {@code TextDelta} / {@code ToolUseStart} /
   * {@code ToolUseStop} / {@code MessageStop} sequence. Callers that need the parsed value invoke
   * {@link ai.singlr.core.schema.StructuredContentParser} on the accumulated assistant text after
   * the final {@link ModelChunk.MessageStop}.
   *
   * <p>Default implementation falls back to the blocking {@link #chat(List, List, OutputSchema)}
   * call and synthesises chunks from the resulting {@link Response}; production providers override
   * to wire the schema into their per-turn streaming dispatch. The cancellation contract is
   * identical to {@link #chatStream(List, List, CancellationToken)}.
   *
   * @param messages the conversation history
   * @param tools available tools the model can call
   * @param outputSchema the schema constraining the model's text output; non-null
   * @param cancellation cooperative cancellation token; signal flowed through to {@code onError}
   * @return a single-subscriber publisher of the normalised chunk sequence
   * @throws NullPointerException if {@code outputSchema} or {@code cancellation} is null
   */
  default Flow.Publisher<ModelChunk> chatStream(
      List<Message> messages,
      List<Tool> tools,
      OutputSchema<?> outputSchema,
      CancellationToken cancellation) {
    Objects.requireNonNull(outputSchema, "outputSchema must not be null");
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    return chatStreamWithCapturedSchema(messages, tools, outputSchema, cancellation);
  }

  /**
   * Wildcard-capture helper for the default {@link #chatStream(List, List, OutputSchema,
   * CancellationToken)} implementation. The signature's {@code <T>} captures the call-site wildcard
   * so {@link #chat(List, List, OutputSchema)} can be invoked without an unchecked cast; the typed
   * parsed value is then discarded because streaming consumers accumulate the assistant text and
   * parse it once after the final {@link ModelChunk.MessageStop}.
   */
  private <T> Flow.Publisher<ModelChunk> chatStreamWithCapturedSchema(
      List<Message> messages,
      List<Tool> tools,
      OutputSchema<T> outputSchema,
      CancellationToken cancellation) {
    var typed = chat(messages, tools, outputSchema);
    var erased =
        Response.newBuilder()
            .withContent(typed.content())
            .withToolCalls(typed.toolCalls())
            .withFinishReason(typed.finishReason())
            .withUsage(typed.usage())
            .withThinking(typed.thinking())
            .withCitations(typed.citations())
            .withMetadata(typed.metadata())
            .build();
    return subscriber -> deliverDefaultChunkSequence(erased, cancellation, subscriber);
  }

  /**
   * Synchronous best-effort delivery of the default-impl chunk sequence to a single subscriber.
   * Internal helper for the default {@link #chatStream(List, List, CancellationToken)} body;
   * provider overrides supply their own publishers and do not use this.
   */
  private static void deliverDefaultChunkSequence(
      Response<Void> response,
      CancellationToken cancellation,
      Flow.Subscriber<? super ModelChunk> subscriber) {
    Objects.requireNonNull(subscriber, "subscriber must not be null");
    var chunks = new ArrayList<ModelChunk>();
    if (response.content() != null && !response.content().isEmpty()) {
      chunks.add(new ModelChunk.TextDelta(response.content()));
    }
    for (var call : response.toolCalls()) {
      chunks.add(new ModelChunk.ToolUseStart(call.id(), call.name()));
      chunks.add(new ModelChunk.ToolUseStop(call));
    }
    var stopReason =
        response.finishReason() != null ? response.finishReason().name() : FinishReason.STOP.name();
    var usage = response.usage() != null ? response.usage() : Response.Usage.of(0, 0);
    var metadata =
        response.metadata() != null ? response.metadata() : java.util.Map.<String, String>of();
    chunks.add(new ModelChunk.MessageStop(stopReason, usage, metadata));

    var subscriptionActive = new AtomicBoolean(true);
    subscriber.onSubscribe(
        new Flow.Subscription() {
          private int index = 0;

          @Override
          public void request(long n) {
            if (!subscriptionActive.get()) {
              return;
            }
            if (n <= 0) {
              if (subscriptionActive.compareAndSet(true, false)) {
                subscriber.onError(
                    new IllegalArgumentException("non-positive subscription request: " + n));
              }
              return;
            }
            if (cancellation.isCancelled()) {
              if (subscriptionActive.compareAndSet(true, false)) {
                subscriber.onError(new CancellationException(cancellation.reason().orElseThrow()));
              }
              return;
            }
            while (n > 0 && index < chunks.size() && subscriptionActive.get()) {
              subscriber.onNext(chunks.get(index++));
              n--;
            }
            if (index >= chunks.size() && subscriptionActive.compareAndSet(true, false)) {
              subscriber.onComplete();
            }
          }

          @Override
          public void cancel() {
            subscriptionActive.set(false);
          }
        });
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
