/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.runtime.CancellationToken;
import java.util.concurrent.Flow;

/**
 * How one turn of a provider's request runs: as the publisher of its event stream. {@link
 * ChatExchange} is the exchange every streaming provider shares; a provider whose turns span
 * several requests decorates it.
 *
 * @param <R> the provider's request type
 */
@FunctionalInterface
public interface Exchange<R> {

  /**
   * The events answering {@code request}, for one subscriber. Nothing is sent before the
   * subscriber's first request. The stream ends with exactly one {@link StreamEvent.Done} or {@link
   * StreamEvent.Error} and then {@code onComplete}; a failure to send the request is its one error
   * event. Cancelling the subscription aborts the request and closes its response body; cancelling
   * {@code cancellation} does the same and ends the stream with {@code onError} carrying a {@link
   * java.util.concurrent.CancellationException}.
   *
   * @param request the provider's request
   * @param cancellation ends the stream once cancelled
   * @return the turn's events
   */
  Flow.Publisher<StreamEvent> stream(R request, CancellationToken cancellation);
}
