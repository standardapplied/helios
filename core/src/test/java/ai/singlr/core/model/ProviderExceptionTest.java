/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ProviderExceptionTest {

  @Test
  void messageOnlyConstructorSetsStatusZero() {
    var ex = new ProviderException("fail");
    assertEquals("fail", ex.getMessage());
    assertEquals(0, ex.statusCode());
  }

  @Test
  void messageAndCauseConstructor() {
    var cause = new RuntimeException("root");
    var ex = new ProviderException("fail", cause);
    assertEquals("fail", ex.getMessage());
    assertSame(cause, ex.getCause());
    assertEquals(0, ex.statusCode());
  }

  @Test
  void messageAndStatusConstructor() {
    var ex = new ProviderException("not found", 404);
    assertEquals(404, ex.statusCode());
  }

  @Test
  void fullConstructor() {
    var cause = new RuntimeException("root");
    var ex = new ProviderException("error", 502, cause);
    assertEquals(502, ex.statusCode());
    assertSame(cause, ex.getCause());
  }

  @Test
  void isClientError() {
    assertTrue(new ProviderException("x", 400).isClientError());
    assertTrue(new ProviderException("x", 429).isClientError());
    assertTrue(new ProviderException("x", 499).isClientError());
    assertFalse(new ProviderException("x", 500).isClientError());
    assertFalse(new ProviderException("x", 0).isClientError());
  }

  @Test
  void isServerError() {
    assertTrue(new ProviderException("x", 500).isServerError());
    assertTrue(new ProviderException("x", 503).isServerError());
    assertFalse(new ProviderException("x", 400).isServerError());
    assertFalse(new ProviderException("x", 0).isServerError());
  }

  @Test
  void isRetryableCoversAllCases() {
    assertTrue(new ProviderException("x", 0).isRetryable(), "network error");
    assertTrue(new ProviderException("x", 408).isRetryable(), "request timeout");
    assertTrue(new ProviderException("x", 429).isRetryable(), "rate limit");
    assertTrue(new ProviderException("x", 529).isRetryable(), "overloaded");
    assertTrue(new ProviderException("x", 500).isRetryable(), "server error");
    assertTrue(new ProviderException("x", 503).isRetryable(), "service unavailable");
    assertFalse(new ProviderException("x", 400).isRetryable(), "bad request");
    assertFalse(new ProviderException("x", 401).isRetryable(), "unauthorized");
    assertFalse(new ProviderException("x", 404).isRetryable(), "not found");
  }
}
