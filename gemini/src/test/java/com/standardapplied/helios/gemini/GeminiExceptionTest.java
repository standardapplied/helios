/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeminiExceptionTest {

  @Test
  void messageOnly() {
    var exception = new GeminiException("Error occurred");

    assertEquals("Error occurred", exception.getMessage());
    assertEquals(0, exception.statusCode());
    assertNull(exception.getCause());
    assertFalse(exception.isClientError());
    assertFalse(exception.isServerError());
  }

  @Test
  void messageWithCause() {
    var cause = new RuntimeException("Root cause");

    var exception = new GeminiException("Error occurred", cause);

    assertEquals("Error occurred", exception.getMessage());
    assertEquals(cause, exception.getCause());
    assertEquals(0, exception.statusCode());
  }

  @Test
  void messageWithStatusCode() {
    var exception = new GeminiException("Bad request", 400);

    assertEquals("Bad request", exception.getMessage());
    assertEquals(400, exception.statusCode());
    assertTrue(exception.isClientError());
    assertFalse(exception.isServerError());
  }

  @Test
  void messageWithStatusCodeAndCause() {
    var cause = new RuntimeException("Cause");

    var exception = new GeminiException("Server error", 500, cause);

    assertEquals("Server error", exception.getMessage());
    assertEquals(500, exception.statusCode());
    assertEquals(cause, exception.getCause());
    assertFalse(exception.isClientError());
    assertTrue(exception.isServerError());
  }

  @Test
  void clientErrorRange() {
    assertTrue(new GeminiException("", 400).isClientError());
    assertTrue(new GeminiException("", 401).isClientError());
    assertTrue(new GeminiException("", 403).isClientError());
    assertTrue(new GeminiException("", 404).isClientError());
    assertTrue(new GeminiException("", 429).isClientError());
    assertTrue(new GeminiException("", 499).isClientError());
    assertFalse(new GeminiException("", 500).isClientError());
  }

  @Test
  void serverErrorRange() {
    assertTrue(new GeminiException("", 500).isServerError());
    assertTrue(new GeminiException("", 502).isServerError());
    assertTrue(new GeminiException("", 503).isServerError());
    assertTrue(new GeminiException("", 599).isServerError());
    assertFalse(new GeminiException("", 400).isServerError());
  }

  @Test
  void isRetryableNetworkError() {
    assertTrue(new GeminiException("timeout").isRetryable());
    assertTrue(new GeminiException("timeout", new RuntimeException()).isRetryable());
  }

  @Test
  void isRetryableRequestTimeout() {
    assertTrue(new GeminiException("", 408).isRetryable());
  }

  @Test
  void isRetryableRateLimited() {
    assertTrue(new GeminiException("", 429).isRetryable());
  }

  @Test
  void isRetryableServerErrors() {
    assertTrue(new GeminiException("", 500).isRetryable());
    assertTrue(new GeminiException("", 502).isRetryable());
    assertTrue(new GeminiException("", 503).isRetryable());
    assertTrue(new GeminiException("", 504).isRetryable());
    assertTrue(new GeminiException("", 599).isRetryable());
  }

  @Test
  void isNotRetryableForClientErrors() {
    assertFalse(new GeminiException("", 400).isRetryable());
    assertFalse(new GeminiException("", 401).isRetryable());
    assertFalse(new GeminiException("", 403).isRetryable());
    assertFalse(new GeminiException("", 404).isRetryable());
    assertFalse(new GeminiException("", 422).isRetryable());
  }
}
