/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.TransientStreamException;
import java.io.IOException;

/**
 * The exception a failed turn ends in, one way for every provider: the provider's own exception and
 * a {@link TransientStreamException} pass through, an {@link IOException} becomes a retryable
 * {@link TransientStreamException} naming the provider, and anything else becomes the provider's
 * exception.
 *
 * @param providerName the provider name a {@link TransientStreamException} carries
 * @param failure creates the provider's exception
 */
record StreamFailure(String providerName, ProviderFailure failure) {

  /** The exception {@code error} ends its turn in. */
  RuntimeException of(StreamEvent.Error error) {
    return switch (error.cause()) {
      case ProviderException own -> own;
      case TransientStreamException transientFailure -> transientFailure;
      case IOException io -> new TransientStreamException(error.message(), io, providerName);
      case null, default -> failure.create(error.message(), 0, error.cause());
    };
  }

  /** The exception of a stream that completed without its terminal event. */
  RuntimeException unterminated() {
    return failure.create("Stream ended without completion event", 0, null);
  }
}
