/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.StreamEvent;

/**
 * How one turn of a provider's request runs: as its stream of events. A provider passes its
 * streams' opener, whose {@link SseReader} sends the request on the first {@code hasNext()}; a
 * provider whose turns span several requests decorates it.
 *
 * @param <R> the provider's request type
 */
@FunctionalInterface
public interface Exchange<R> {

  /**
   * The events answering {@code request}. Nothing is sent before the first {@code hasNext()}.
   * {@code close()}, from any thread, at any time and idempotent, aborts the request whether it
   * waits for its headers or reads its body; the stream then ends, a {@code hasNext()} blocked on
   * another thread in an error event or a {@link java.util.concurrent.CancellationException}. An
   * interrupt of the reading thread throws a {@code CancellationException} with the interrupt kept.
   * Otherwise the stream ends with exactly one {@link StreamEvent.Done} or {@link
   * StreamEvent.Error}; a failure to send the request is its one error event.
   *
   * @param request the provider's request
   * @return the turn's events, which the caller closes
   */
  CloseableIterator<StreamEvent> stream(R request);
}
