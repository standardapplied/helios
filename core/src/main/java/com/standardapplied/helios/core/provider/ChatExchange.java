/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.schema.StructuredContentParser;
import java.io.IOException;
import java.util.List;

/**
 * The call flow every streaming provider shares, composed into each {@code Model}. A blocking call
 * opens a stream and drains it to its {@link Response}; a streaming call hands back the stream,
 * with a failure to open it as its one {@link StreamEvent.Error}; a structured call parses the
 * drained content against its schema unless the turn called tools. Failures map one way for every
 * provider: the provider's own exception and a {@link TransientStreamException} pass through, an
 * {@link IOException} becomes a retryable {@link TransientStreamException} naming the provider, an
 * interrupt is restored and becomes the provider's exception, and anything else becomes the
 * provider's exception.
 *
 * @param <R> the provider's request type
 */
public final class ChatExchange<R> {

  /**
   * Opens one response stream for a request.
   *
   * @param <R> the provider's request type
   */
  @FunctionalInterface
  public interface StreamOpener<R> {

    /**
     * The stream answering {@code request}.
     *
     * @throws IOException if the request cannot be sent
     * @throws InterruptedException if interrupted while sending
     */
    CloseableIterator<StreamEvent> open(R request) throws IOException, InterruptedException;
  }

  private final String providerName;
  private final String apiName;
  private final StreamOpener<R> opener;
  private final ProviderFailure failure;
  private final StructuredContentParser.JsonAdapter json;
  private final RawOutputCapturePolicy capturePolicy;

  /**
   * An exchange for one provider.
   *
   * @param providerName the provider name a {@link TransientStreamException} carries
   * @param apiName the API a failure to communicate names, e.g. {@code "Anthropic API"}
   * @param opener opens a stream for a request
   * @param failure creates the provider's exception
   * @param json the provider's JSON binding, for structured output
   * @param capturePolicy whether a structured-output failure keeps the raw output
   */
  public ChatExchange(
      String providerName,
      String apiName,
      StreamOpener<R> opener,
      ProviderFailure failure,
      StructuredContentParser.JsonAdapter json,
      RawOutputCapturePolicy capturePolicy) {
    this.providerName = providerName;
    this.apiName = apiName;
    this.opener = opener;
    this.failure = failure;
    this.json = json;
    this.capturePolicy = capturePolicy;
  }

  /** Opens a stream for {@code request} and drains it to its response. */
  public Response<Void> chat(R request) {
    return chat(opener, request);
  }

  /** Has {@code streams} open a stream for {@code request} and drains it to its response. */
  public Response<Void> chat(StreamOpener<R> streams, R request) {
    try (var events = streams.open(request)) {
      return drain(events);
    } catch (IOException e) {
      throw new TransientStreamException("Failed to communicate with " + apiName, e, providerName);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw failure.create("Request interrupted", 0, e);
    }
  }

  /**
   * {@code response} typed by {@code schema}, its content parsed when the turn called no tools. A
   * tool-calling turn is intermediate: its prose is not the structured answer, which a later
   * text-only turn delivers.
   */
  public <T> Response<T> structured(Response<Void> response, OutputSchema<T> schema) {
    var parsed = response.toolCalls().isEmpty() ? parse(response.content(), schema) : null;
    return Response.<T>newBuilder(schema.type())
        .withContent(response.content())
        .withParsed(parsed)
        .withToolCalls(response.toolCalls())
        .withFinishReason(response.finishReason())
        .withUsage(response.usage())
        .withThinking(response.thinking())
        .withCitations(response.citations())
        .withMetadata(response.metadata())
        .build();
  }

  /** {@code content} parsed and validated against {@code schema}. */
  public <T> T parse(String content, OutputSchema<T> schema) {
    return StructuredContentParser.parse(content, schema, json, capturePolicy);
  }

  /** The stream for {@code request}, or a stream of the one error that kept it from opening. */
  public CloseableIterator<StreamEvent> stream(R request) {
    return stream(opener, request);
  }

  /**
   * The stream {@code streams} opens for {@code request}, or a stream of the one error that kept it
   * from opening.
   */
  public CloseableIterator<StreamEvent> stream(StreamOpener<R> streams, R request) {
    try {
      return streams.open(request);
    } catch (ProviderException e) {
      return failed(new StreamEvent.Error(e.getMessage(), e));
    } catch (IOException e) {
      return failed(new StreamEvent.Error("Failed to connect", e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return failed(new StreamEvent.Error("Request interrupted", e));
    }
  }

  @SuppressWarnings("unchecked")
  private Response<Void> drain(CloseableIterator<StreamEvent> events) {
    while (events.hasNext()) {
      var event = events.next();
      if (event instanceof StreamEvent.Done(var response)) {
        return (Response<Void>) response;
      }
      if (event instanceof StreamEvent.Error(var message, var cause)) {
        throw failureOf(message, cause);
      }
    }
    throw failure.create("Stream ended without completion event", 0, null);
  }

  private RuntimeException failureOf(String message, Exception cause) {
    return switch (cause) {
      case ProviderException own -> own;
      case TransientStreamException transientFailure -> transientFailure;
      case IOException io -> new TransientStreamException(message, io, providerName);
      case null, default -> failure.create(message, 0, cause);
    };
  }

  private static CloseableIterator<StreamEvent> failed(StreamEvent.Error error) {
    return CloseableIterator.of(List.<StreamEvent>of(error).iterator());
  }
}
