/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;

/**
 * How one turn of a provider's request runs: drained to its response, or handed back as its event
 * stream. {@link ChatExchange} is the exchange every streaming provider shares; a provider whose
 * turns span several requests decorates it.
 *
 * @param <R> the provider's request type
 */
public interface Exchange<R> {

  /**
   * The response to {@code request}.
   *
   * @param request the provider's request
   * @return the completed turn
   * @throws com.standardapplied.helios.core.model.ProviderException if the provider fails the turn
   * @throws com.standardapplied.helios.core.model.TransientStreamException if the stream breaks
   */
  Response<Void> chat(R request);

  /**
   * The events answering {@code request}; a failure to open the stream is its one {@link
   * StreamEvent.Error}.
   *
   * @param request the provider's request
   * @return the turn's events, ending with {@link StreamEvent.Done} or {@link StreamEvent.Error}
   */
  CloseableIterator<StreamEvent> stream(R request);
}
