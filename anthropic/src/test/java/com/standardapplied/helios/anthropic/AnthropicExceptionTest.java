/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnthropicExceptionTest {

  @Test
  void messageOnly() {
    var exception = new AnthropicException("Error occurred");

    assertEquals("Error occurred", exception.getMessage());
    assertEquals(0, exception.statusCode());
    assertNull(exception.getCause());
    assertFalse(exception.isClientError());
    assertFalse(exception.isServerError());
  }

  @Test
  void messageWithCause() {
    var cause = new RuntimeException("Root cause");

    var exception = new AnthropicException("Error occurred", cause);

    assertEquals("Error occurred", exception.getMessage());
    assertEquals(cause, exception.getCause());
    assertEquals(0, exception.statusCode());
  }

  @Test
  void messageWithStatusCode() {
    var exception = new AnthropicException("Bad request", 400);

    assertEquals("Bad request", exception.getMessage());
    assertEquals(400, exception.statusCode());
    assertTrue(exception.isClientError());
    assertFalse(exception.isServerError());
  }

  @Test
  void messageWithStatusCodeAndCause() {
    var cause = new RuntimeException("Cause");

    var exception = new AnthropicException("Server error", 500, cause);

    assertEquals("Server error", exception.getMessage());
    assertEquals(500, exception.statusCode());
    assertEquals(cause, exception.getCause());
    assertFalse(exception.isClientError());
    assertTrue(exception.isServerError());
  }

  @Test
  void clientErrorRange() {
    assertTrue(new AnthropicException("", 400).isClientError());
    assertTrue(new AnthropicException("", 401).isClientError());
    assertTrue(new AnthropicException("", 403).isClientError());
    assertTrue(new AnthropicException("", 404).isClientError());
    assertTrue(new AnthropicException("", 429).isClientError());
    assertTrue(new AnthropicException("", 499).isClientError());
    assertFalse(new AnthropicException("", 500).isClientError());
  }

  @Test
  void serverErrorRange() {
    assertTrue(new AnthropicException("", 500).isServerError());
    assertTrue(new AnthropicException("", 502).isServerError());
    assertTrue(new AnthropicException("", 503).isServerError());
    assertTrue(new AnthropicException("", 599).isServerError());
    assertFalse(new AnthropicException("", 400).isServerError());
  }

  @Test
  void isRetryableNetworkError() {
    assertTrue(new AnthropicException("timeout").isRetryable());
    assertTrue(new AnthropicException("timeout", new RuntimeException()).isRetryable());
  }

  @Test
  void isRetryableRequestTimeout() {
    assertTrue(new AnthropicException("", 408).isRetryable());
  }

  @Test
  void isRetryableRateLimited() {
    assertTrue(new AnthropicException("", 429).isRetryable());
  }

  @Test
  void isRetryableOverloaded() {
    assertTrue(new AnthropicException("", 529).isRetryable());
  }

  @Test
  void isRetryableServerErrors() {
    assertTrue(new AnthropicException("", 500).isRetryable());
    assertTrue(new AnthropicException("", 502).isRetryable());
    assertTrue(new AnthropicException("", 503).isRetryable());
    assertTrue(new AnthropicException("", 504).isRetryable());
    assertTrue(new AnthropicException("", 599).isRetryable());
  }

  @Test
  void isNotRetryableForClientErrors() {
    assertFalse(new AnthropicException("", 400).isRetryable());
    assertFalse(new AnthropicException("", 401).isRetryable());
    assertFalse(new AnthropicException("", 403).isRetryable());
    assertFalse(new AnthropicException("", 404).isRetryable());
    assertFalse(new AnthropicException("", 422).isRetryable());
  }
}
