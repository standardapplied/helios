/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.TransientStreamException;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * The {@link Exchange} every streaming provider shares. A blocking call opens a stream and drains
 * it to its {@link Response}; a streaming call hands back the stream, with a failure to open it as
 * its one {@link StreamEvent.Error}. Failures map one way for every provider: the provider's own
 * exception and a {@link TransientStreamException} pass through, an {@link IOException} becomes a
 * retryable {@link TransientStreamException} naming the provider, an interrupt is restored and
 * becomes the provider's exception, and anything else becomes the provider's exception.
 *
 * @param <R> the provider's request type
 */
public final class ChatExchange<R> implements Exchange<R> {

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

  /**
   * An exchange for one provider.
   *
   * @param providerName the provider name a {@link TransientStreamException} carries
   * @param apiName the API a failure to communicate names, e.g. {@code "Anthropic API"}
   * @param opener opens a stream for a request
   * @param failure creates the provider's exception
   */
  public ChatExchange(
      String providerName, String apiName, StreamOpener<R> opener, ProviderFailure failure) {
    this.providerName = Objects.requireNonNull(providerName, "providerName must not be null");
    this.apiName = Objects.requireNonNull(apiName, "apiName must not be null");
    this.opener = Objects.requireNonNull(opener, "opener must not be null");
    this.failure = Objects.requireNonNull(failure, "failure must not be null");
  }

  /** Opens a stream for {@code request} and drains it to its response. */
  @Override
  public Response<Void> chat(R request) {
    try (var events = opener.open(request)) {
      return drain(events);
    } catch (IOException e) {
      throw new TransientStreamException("Failed to communicate with " + apiName, e, providerName);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw failure.create("Request interrupted", 0, e);
    }
  }

  /** The stream for {@code request}, or a stream of the one error that kept it from opening. */
  @Override
  public CloseableIterator<StreamEvent> stream(R request) {
    try {
      return opener.open(request);
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
