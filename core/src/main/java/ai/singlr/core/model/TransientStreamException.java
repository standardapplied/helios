/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import ai.singlr.core.common.Strings;
import java.util.Objects;

/**
 * Thrown by a {@link Model} when a streaming response is interrupted by a recoverable transport
 * failure — a socket reset mid-SSE, a half-closed connection, an idle-read timeout that the
 * provider itself classifies as retryable (HTTP 408 / 429 / 5xx / network-error 0).
 *
 * <p>Distinguished from generic provider exceptions ({@code AnthropicException}, {@code
 * GeminiException}, {@code OpenAIException}) so the session loop can pattern-match on the type and
 * apply a bounded retry without coupling to provider-specific class hierarchies. Non- transient
 * failures — schema validation, malformed responses, 4xx other than 408/429, tool-call shape errors
 * — must continue to surface as their existing provider exceptions and terminate the run normally.
 *
 * <p>The originating low-level throwable (e.g. {@code java.io.IOException("Connection reset by
 * peer")}) is preserved via {@link #getCause()} so the session terminal can walk the chain through
 * {@code SerializedError.of(Throwable)} and surface the byte-level failure mode to the deployer
 * instead of collapsing every transport variant to a single opaque {@code "Stream read error"}
 * string.
 *
 * <p>{@link #providerName()} carries the provider's short identifier (e.g. {@code "anthropic"}) so
 * the session terminal can attribute the failure without the session loop having to inspect the
 * {@link Model#provider()} call site.
 */
public class TransientStreamException extends RuntimeException {

  private final String providerName;

  /**
   * @param message a human-readable description of the failure; non-blank
   * @param cause the originating throwable (typically a {@code java.io.IOException}); may be {@code
   *     null} when the provider has no underlying throwable to attach
   * @param providerName the provider's short identifier as returned by {@link Model#provider()};
   *     non-blank
   * @throws NullPointerException if {@code message} or {@code providerName} is null
   * @throws IllegalArgumentException if {@code message} or {@code providerName} is blank
   */
  public TransientStreamException(String message, Throwable cause, String providerName) {
    super(Objects.requireNonNull(message, "message must not be null"), cause);
    if (Strings.isBlank(message)) {
      throw new IllegalArgumentException("message must not be blank");
    }
    Objects.requireNonNull(providerName, "providerName must not be null");
    if (Strings.isBlank(providerName)) {
      throw new IllegalArgumentException("providerName must not be blank");
    }
    this.providerName = providerName;
  }

  /**
   * The provider's short identifier (e.g. {@code "anthropic"}).
   *
   * @return the provider name; never null or blank
   */
  public String providerName() {
    return providerName;
  }
}
