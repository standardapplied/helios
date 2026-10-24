/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.model;

import com.standardapplied.helios.core.common.Strings;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Thrown by a {@link Model} when a streaming response fails in a way the same request may survive
 * if sent again: a transport failure (a socket reset mid-SSE, a half-closed connection), a 408, 429
 * or 5xx answer to the stream request (cause: the provider's exception carrying the status, {@link
 * #retryAfter()}: the answer's {@code Retry-After} seconds), or an error event the provider marks
 * retryable mid-stream (Anthropic's {@code overloaded_error}, {@code api_error}, {@code
 * timeout_error} and {@code rate_limit_error}, OpenAI's {@code server_error} and {@code
 * rate_limit_exceeded}, Gemini's numeric 408, 429 or 5xx code).
 *
 * <p>Distinguished from generic {@link ProviderException} subclasses so the session loop can
 * pattern-match on the type and apply a bounded retry without coupling to provider-specific class
 * hierarchies. Non-transient failures — schema validation, malformed responses, 4xx other than
 * 408/429, tool-call shape errors — must continue to surface as their existing provider exceptions
 * and terminate the run normally.
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
  private final Duration retryAfter;

  /**
   * A failure with no {@code Retry-After}.
   *
   * @param message a human-readable description of the failure; non-blank
   * @param cause the originating throwable (typically a {@code java.io.IOException}); may be {@code
   *     null} when the provider has no underlying throwable to attach
   * @param providerName the provider's short identifier as returned by {@link Model#provider()};
   *     non-blank
   * @throws NullPointerException if {@code message} or {@code providerName} is null
   * @throws IllegalArgumentException if {@code message} or {@code providerName} is blank
   */
  public TransientStreamException(String message, Throwable cause, String providerName) {
    this(message, cause, providerName, Optional.empty());
  }

  /**
   * A failure whose answer said how long to wait before sending the request again.
   *
   * @param message a human-readable description of the failure; non-blank
   * @param cause the originating throwable; may be {@code null}
   * @param providerName the provider's short identifier as returned by {@link Model#provider()};
   *     non-blank
   * @param retryAfter the wait the provider asked for, or empty; a present value is non-negative
   * @throws NullPointerException if {@code message}, {@code providerName} or {@code retryAfter} is
   *     null
   * @throws IllegalArgumentException if {@code message} or {@code providerName} is blank, or {@code
   *     retryAfter} is negative
   */
  public TransientStreamException(
      String message, Throwable cause, String providerName, Optional<Duration> retryAfter) {
    super(Objects.requireNonNull(message, "message must not be null"), cause);
    if (Strings.isBlank(message)) {
      throw new IllegalArgumentException("message must not be blank");
    }
    Objects.requireNonNull(providerName, "providerName must not be null");
    if (Strings.isBlank(providerName)) {
      throw new IllegalArgumentException("providerName must not be blank");
    }
    Objects.requireNonNull(retryAfter, "retryAfter must not be null");
    if (retryAfter.filter(Duration::isNegative).isPresent()) {
      throw new IllegalArgumentException(
          "retryAfter must not be negative, got " + retryAfter.get());
    }
    this.providerName = providerName;
    this.retryAfter = retryAfter.orElse(null);
  }

  /**
   * The provider's short identifier (e.g. {@code "anthropic"}).
   *
   * @return the provider name; never null or blank
   */
  public String providerName() {
    return providerName;
  }

  /**
   * How long the provider asked to wait before the request is sent again: the {@code Retry-After}
   * seconds of a 408, 429 or 5xx answer to the stream request.
   *
   * @return the wait, or empty when the provider named none
   */
  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
