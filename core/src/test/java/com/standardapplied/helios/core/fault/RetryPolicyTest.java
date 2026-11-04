/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RetryPolicyTest {

  @Test
  void successfulOperationNoRetry() throws Exception {
    var policy = RetryPolicy.newBuilder().withMaxAttempts(3).build();
    var attempts = new AtomicInteger(0);

    var result =
        policy.execute(
            () -> {
              attempts.incrementAndGet();
              return "success";
            });

    assertEquals("success", result);
    assertEquals(1, attempts.get());
  }

  @Test
  void retryUntilSuccess() throws Exception {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .build();
    var attempts = new AtomicInteger(0);

    var result =
        policy.execute(
            () -> {
              if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("Fail");
              }
              return "success";
            });

    assertEquals("success", result);
    assertEquals(3, attempts.get());
  }

  @Test
  void exhaustRetries() {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(3)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .build();
    var attempts = new AtomicInteger(0);

    var exception =
        assertThrows(
            RetryExhaustedException.class,
            () ->
                policy.execute(
                    () -> {
                      attempts.incrementAndGet();
                      throw new RuntimeException("Always fails");
                    }));

    assertEquals(3, attempts.get());
    assertEquals(3, exception.attempts());
    assertInstanceOf(RuntimeException.class, exception.getCause());
  }

  @Test
  void retryOnlySpecificExceptions() {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .withRetryOn(IOException.class)
            .build();
    var attempts = new AtomicInteger(0);

    var exception =
        assertThrows(
            RetryExhaustedException.class,
            () ->
                policy.execute(
                    () -> {
                      attempts.incrementAndGet();
                      throw new IllegalArgumentException("Not retryable");
                    }));

    assertEquals(1, attempts.get());
    assertInstanceOf(IllegalArgumentException.class, exception.getCause());
  }

  @Test
  void retryOnSpecificExceptionMatches() throws Exception {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .withRetryOn(IOException.class)
            .build();
    var attempts = new AtomicInteger(0);

    var result =
        policy.execute(
            () -> {
              if (attempts.incrementAndGet() < 3) {
                throw new IOException("Retryable");
              }
              return "success";
            });

    assertEquals("success", result);
    assertEquals(3, attempts.get());
  }

  @Test
  void retryOnMultipleExceptionTypes() throws Exception {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .withRetryOn(IOException.class, IllegalStateException.class)
            .build();
    var attempts = new AtomicInteger(0);

    var result =
        policy.execute(
            () -> {
              var count = attempts.incrementAndGet();
              if (count == 1) {
                throw new IOException("IO error");
              }
              if (count == 2) {
                throw new IllegalStateException("State error");
              }
              return "success";
            });

    assertEquals("success", result);
    assertEquals(3, attempts.get());
  }

  @Test
  void customRetryPredicate() throws Exception {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .withRetryOn(t -> t.getMessage().contains("retry"))
            .build();
    var attempts = new AtomicInteger(0);

    var result =
        policy.execute(
            () -> {
              if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("Please retry");
              }
              return "success";
            });

    assertEquals("success", result);
    assertEquals(3, attempts.get());
  }

  @Test
  void customRetryPredicateStopsOnNonMatch() {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .withRetryOn(t -> t.getMessage().contains("retry"))
            .build();
    var attempts = new AtomicInteger(0);

    assertThrows(
        RetryExhaustedException.class,
        () ->
            policy.execute(
                () -> {
                  attempts.incrementAndGet();
                  throw new RuntimeException("No match here");
                }));

    assertEquals(1, attempts.get());
  }

  @Test
  void executeRunnableSuccess() throws Exception {
    var policy = RetryPolicy.newBuilder().withMaxAttempts(3).build();
    var executed = new AtomicInteger(0);

    policy.execute(executed::incrementAndGet);

    assertEquals(1, executed.get());
  }

  @Test
  void executeRunnableWithRetry() throws Exception {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .build();
    var attempts = new AtomicInteger(0);

    policy.execute(
        () -> {
          if (attempts.incrementAndGet() < 3) {
            throw new RuntimeException("Fail");
          }
        });

    assertEquals(3, attempts.get());
  }

  @Test
  void interruptedDuringRetry() {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofSeconds(10)))
            .build();
    var attempts = new AtomicInteger(0);

    Thread.currentThread().interrupt();

    assertThrows(
        InterruptedException.class,
        () ->
            policy.execute(
                () -> {
                  attempts.incrementAndGet();
                  throw new RuntimeException("Fail");
                }));

    assertTrue(Thread.interrupted());
  }

  @Test
  void defaultValues() {
    var policy = RetryPolicy.newBuilder().build();

    assertEquals(3, policy.maxAttempts());
    assertInstanceOf(Backoff.Exponential.class, policy.backoff());
    assertEquals(0.1, policy.jitter());
  }

  @Test
  void operationThrowsInterruptedException() {
    var policy = RetryPolicy.newBuilder().withMaxAttempts(3).build();

    assertThrows(
        InterruptedException.class,
        () ->
            policy.execute(
                () -> {
                  throw new InterruptedException("interrupted");
                }));

    assertTrue(Thread.interrupted());
  }

  private static final Backoff BACKOFF = Backoff.fixed(Duration.ofMillis(1));
  private static final Predicate<Throwable> ANY = t -> true;

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void maxAttemptsBelowOneIsRejected(int maxAttempts) {
    var message = "maxAttempts must be >= 1, got " + maxAttempts;
    assertRejected(
        IllegalArgumentException.class,
        message,
        () -> new RetryPolicy(maxAttempts, BACKOFF, 0.1, ANY));
    assertRejected(
        IllegalArgumentException.class,
        message,
        () -> RetryPolicy.newBuilder().withMaxAttempts(maxAttempts).build());
  }

  @Test
  void nullBackoffIsRejected() {
    var message = "backoff must not be null";
    assertRejected(NullPointerException.class, message, () -> new RetryPolicy(3, null, 0.1, ANY));
    assertRejected(
        NullPointerException.class,
        message,
        () -> RetryPolicy.newBuilder().withBackoff(null).build());
  }

  @Test
  void nullRetryPredicateIsRejected() {
    var message = "retryOn must not be null";
    assertRejected(
        NullPointerException.class, message, () -> new RetryPolicy(3, BACKOFF, 0.1, null));
    assertRejected(
        NullPointerException.class,
        message,
        () -> RetryPolicy.newBuilder().withRetryOn((Predicate<Throwable>) null).build());
  }

  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, -0.1, 1.1, Double.POSITIVE_INFINITY})
  void jitterThatIsNotAFiniteFractionIsRejected(double jitter) {
    var message = "jitter must be within 0.0-1.0, got " + jitter;
    assertRejected(
        IllegalArgumentException.class, message, () -> new RetryPolicy(3, BACKOFF, jitter, ANY));
    assertRejected(
        IllegalArgumentException.class,
        message,
        () -> RetryPolicy.newBuilder().withJitter(jitter).build());
  }

  @ParameterizedTest
  @ValueSource(doubles = {0.0, 1.0})
  void jitterAtEitherBoundIsAccepted(double jitter) {
    assertEquals(jitter, new RetryPolicy(1, BACKOFF, jitter, ANY).jitter());
  }

  private static void assertRejected(
      Class<? extends RuntimeException> type, String message, Supplier<RetryPolicy> construction) {
    assertEquals(message, assertThrows(type, construction::get).getMessage());
  }

  @Test
  void recordAccessors() {
    var backoff = Backoff.fixed(Duration.ofSeconds(1));
    var policy =
        RetryPolicy.newBuilder().withMaxAttempts(5).withBackoff(backoff).withJitter(0.2).build();

    assertEquals(5, policy.maxAttempts());
    assertEquals(backoff, policy.backoff());
    assertEquals(0.2, policy.jitter());
  }

  @Test
  void errorEscapesRetryPolicy() {
    // An Error is a host failure: it escapes as itself, never retried or wrapped in
    // RetryExhaustedException (see AGENTS.md "Loop crash semantics and teardown").
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .build();
    var attempts = new AtomicInteger(0);

    assertThrows(
        StackOverflowError.class,
        () ->
            policy.execute(
                () -> {
                  attempts.incrementAndGet();
                  throw new StackOverflowError("simulated");
                }));

    assertEquals(1, attempts.get(), "Error must escape on first attempt, not retry");
  }

  @Test
  void predicateExceptionPreservesOriginalCause() {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .withRetryOn(
                t -> {
                  throw new RuntimeException("predicate exploded");
                })
            .build();
    var attempts = new AtomicInteger(0);

    var exception =
        assertThrows(
            RetryExhaustedException.class,
            () ->
                policy.execute(
                    () -> {
                      attempts.incrementAndGet();
                      throw new IOException("original error");
                    }));

    assertEquals(1, attempts.get());
    assertInstanceOf(IOException.class, exception.getCause());
    assertEquals(1, exception.getCause().getSuppressed().length);
    assertEquals("predicate exploded", exception.getCause().getSuppressed()[0].getMessage());
  }
}
