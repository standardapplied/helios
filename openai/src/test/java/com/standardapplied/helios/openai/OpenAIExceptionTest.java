/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OpenAIExceptionTest {

  @Test
  void messageOnlyConstructor() {
    var ex = new OpenAIException("test error");
    assertEquals("test error", ex.getMessage());
    assertEquals(0, ex.statusCode());
    assertNull(ex.getCause());
  }

  @Test
  void messageAndCauseConstructor() {
    var cause = new RuntimeException("root cause");
    var ex = new OpenAIException("test error", cause);
    assertEquals("test error", ex.getMessage());
    assertEquals(0, ex.statusCode());
    assertEquals(cause, ex.getCause());
  }

  @Test
  void messageAndStatusCodeConstructor() {
    var ex = new OpenAIException("not found", 404);
    assertEquals("not found", ex.getMessage());
    assertEquals(404, ex.statusCode());
    assertNull(ex.getCause());
  }

  @Test
  void fullConstructor() {
    var cause = new RuntimeException("root");
    var ex = new OpenAIException("server error", 500, cause);
    assertEquals("server error", ex.getMessage());
    assertEquals(500, ex.statusCode());
    assertEquals(cause, ex.getCause());
  }

  @Test
  void clientError400() {
    var ex = new OpenAIException("bad request", 400);
    assertTrue(ex.isClientError());
    assertFalse(ex.isServerError());
    assertFalse(ex.isRetryable());
  }

  @Test
  void clientError401() {
    var ex = new OpenAIException("unauthorized", 401);
    assertTrue(ex.isClientError());
    assertFalse(ex.isRetryable());
  }

  @Test
  void clientError403() {
    var ex = new OpenAIException("forbidden", 403);
    assertTrue(ex.isClientError());
    assertFalse(ex.isRetryable());
  }

  @Test
  void clientError404() {
    var ex = new OpenAIException("not found", 404);
    assertTrue(ex.isClientError());
    assertFalse(ex.isRetryable());
  }

  @Test
  void clientError422() {
    var ex = new OpenAIException("unprocessable", 422);
    assertTrue(ex.isClientError());
    assertFalse(ex.isRetryable());
  }

  @Test
  void serverError500() {
    var ex = new OpenAIException("internal", 500);
    assertFalse(ex.isClientError());
    assertTrue(ex.isServerError());
    assertTrue(ex.isRetryable());
  }

  @Test
  void serverError502() {
    var ex = new OpenAIException("bad gateway", 502);
    assertTrue(ex.isServerError());
    assertTrue(ex.isRetryable());
  }

  @Test
  void serverError503() {
    var ex = new OpenAIException("unavailable", 503);
    assertTrue(ex.isServerError());
    assertTrue(ex.isRetryable());
  }

  @Test
  void rateLimited429() {
    var ex = new OpenAIException("rate limited", 429);
    assertTrue(ex.isClientError());
    assertTrue(ex.isRetryable());
  }

  @Test
  void requestTimeout408() {
    var ex = new OpenAIException("timeout", 408);
    assertTrue(ex.isClientError());
    assertTrue(ex.isRetryable());
  }

  @Test
  void networkErrorRetryable() {
    var ex = new OpenAIException("network error");
    assertEquals(0, ex.statusCode());
    assertFalse(ex.isClientError());
    assertFalse(ex.isServerError());
    assertTrue(ex.isRetryable());
  }
}
