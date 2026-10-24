/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.IteratorPublisher;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.runtime.CancellationToken;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * The {@link Exchange} every streaming provider shares: an {@link IteratorPublisher} over the
 * provider's response stream, opened on the subscriber's first request on the thread the
 * subscription owns. A failure to open it is its one {@link StreamEvent.Error}: the provider's
 * exception or a {@link TransientStreamException} as it is, an {@link IOException} as a failure to
 * communicate with the API, an interrupt restored. Cancelling interrupts a request awaiting its
 * headers and closes a body being read.
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

  private final String apiName;
  private final StreamOpener<R> opener;

  /**
   * An exchange for one provider.
   *
   * @param apiName the API a failure to communicate names, e.g. {@code "Anthropic API"}
   * @param opener opens a stream for a request
   */
  public ChatExchange(String apiName, StreamOpener<R> opener) {
    this.apiName = Objects.requireNonNull(apiName, "apiName must not be null");
    this.opener = Objects.requireNonNull(opener, "opener must not be null");
  }

  @Override
  public Flow.Publisher<StreamEvent> stream(R request, CancellationToken cancellation) {
    return new IteratorPublisher<>(() -> open(request), cancellation);
  }

  private CloseableIterator<StreamEvent> open(R request) {
    try {
      return opener.open(request);
    } catch (ProviderException | TransientStreamException e) {
      return failed(e.getMessage(), e);
    } catch (IOException e) {
      return failed("Failed to communicate with " + apiName, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return failed("Request interrupted", e);
    }
  }

  private static CloseableIterator<StreamEvent> failed(String message, Exception cause) {
    var nothing =
        Response.newBuilder().withContent("").withFinishReason(FinishReason.ERROR).build();
    return CloseableIterator.of(
        List.<StreamEvent>of(new StreamEvent.Error(message, cause, nothing)).iterator());
  }
}
