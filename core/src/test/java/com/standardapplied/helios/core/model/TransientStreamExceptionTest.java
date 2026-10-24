/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class TransientStreamExceptionTest {

  @Test
  void preservesMessageCauseAndProviderName() {
    var cause = new IOException("Connection reset by peer");
    var ex = new TransientStreamException("Stream read error", cause, "anthropic");

    assertEquals("Stream read error", ex.getMessage());
    assertSame(cause, ex.getCause());
    assertEquals("anthropic", ex.providerName());
    assertEquals(Optional.empty(), ex.retryAfter());
  }

  @Test
  void carriesTheRetryAfterItWasGiven() {
    var ex =
        new TransientStreamException(
            "API error (status 429): slow", null, "openai", Optional.of(Duration.ZERO));

    assertEquals(Optional.of(Duration.ZERO), ex.retryAfter());
  }

  @Test
  void aFailureWithNoRetryAfterSurvivesSerialization() throws Exception {
    var copy = roundTrip(new TransientStreamException("Stream read error", null, "gemini"));

    assertEquals("Stream read error", copy.getMessage());
    assertEquals("gemini", copy.providerName());
    assertEquals(Optional.empty(), copy.retryAfter());
  }

  @Test
  void aFailureWithARetryAfterSurvivesSerialization() throws Exception {
    var copy =
        roundTrip(
            new TransientStreamException(
                "API error (status 503): busy",
                new ProviderException("busy", 503),
                "gemini",
                Optional.of(Duration.ofSeconds(2))));

    assertEquals(Optional.of(Duration.ofSeconds(2)), copy.retryAfter());
    assertEquals(503, ((ProviderException) copy.getCause()).statusCode());
  }

  @Test
  void nullRetryAfterRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new TransientStreamException("Stream read error", null, "openai", null));
    assertEquals("retryAfter must not be null", ex.getMessage());
  }

  @Test
  void negativeRetryAfterRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new TransientStreamException(
                    "Stream read error", null, "openai", Optional.of(Duration.ofSeconds(-1))));
    assertEquals("retryAfter must not be negative, got PT-1S", ex.getMessage());
  }

  @Test
  void nullCauseIsAllowed() {
    var ex = new TransientStreamException("stream broke", null, "openai");
    assertNull(ex.getCause());
    assertEquals("openai", ex.providerName());
  }

  @Test
  void nullMessageRejected() {
    assertThrows(
        NullPointerException.class, () -> new TransientStreamException(null, null, "anthropic"));
  }

  @Test
  void blankMessageRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new TransientStreamException("   ", null, "anthropic"));
    assertEquals("message must not be blank", ex.getMessage());
  }

  @Test
  void nullProviderNameRejected() {
    assertThrows(
        NullPointerException.class,
        () -> new TransientStreamException("Stream read error", null, null));
  }

  @Test
  void blankProviderNameRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new TransientStreamException("Stream read error", null, "  "));
    assertEquals("providerName must not be blank", ex.getMessage());
  }

  private static TransientStreamException roundTrip(TransientStreamException exception)
      throws IOException, ClassNotFoundException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new ObjectOutputStream(bytes)) {
      out.writeObject(exception);
    }
    try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      return (TransientStreamException) in.readObject();
    }
  }
}
